package games.crescentnetwork.mcp.script;

import games.crescentnetwork.mcp.palette.BlockCatalog;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs an agent-authored build script under GraalJS and hands back what it placed.
 *
 * <p>GraalJS rather than Rhino because Rhino gets modern JavaScript wrong in ways that produce a
 * silently incorrect build: in Rhino 1.8.0 and 1.8.1, a {@code const} declared inside a loop body
 * keeps its first iteration's value forever, in every mode and language version, and {@code let} has
 * no per-iteration closure capture. Model-written scripts lean on both constantly.
 *
 * <p>The sandbox is the engine's own: host access off, no host class lookup, no IO, no threads, no
 * native access. The API reaches the script as polyglot proxies rather than as Java objects, so there
 * is nothing for a script to walk back into the JVM through. A runaway script is stopped by
 * cancelling its context from a watchdog, which is the only thing that interrupts a spinning loop.
 *
 * <p>Runs interpreted on a stock JVM, which is fine here: the script calls {@code box()} once and the
 * expansion loop that follows runs at native speed in Java.
 */
public final class ScriptRunner {

    private static final String SOURCE_NAME = "build.js";

    /**
     * Patterns that only appear in TypeScript. Chosen for precision rather than coverage: a false
     * positive would reject valid JavaScript, whereas a false negative just falls through to the
     * engine and surfaces as an ordinary syntax error, which is still actionable.
     */
    private static final List<Pattern> TYPESCRIPT_MARKERS = List.of(
        Pattern.compile("^\\s*(export\\s+)?interface\\s+\\w+\\s*\\{", Pattern.MULTILINE),
        Pattern.compile("^\\s*(export\\s+)?type\\s+\\w+\\s*=", Pattern.MULTILINE),
        Pattern.compile("^\\s*(export\\s+)?enum\\s+\\w+\\s*\\{", Pattern.MULTILINE),
        Pattern.compile("\\bimport\\s+type\\b"),
        Pattern.compile("\\b(let|const|var)\\s+\\w+\\s*:\\s*(string|number|boolean|any|unknown|void|never)\\b"),
        Pattern.compile("\\)\\s*:\\s*(string|number|boolean|any|unknown|void|never)\\s*(\\{|=>)"),
        Pattern.compile("\\bfunction\\s+\\w+\\s*\\([^)]*\\w+\\s*:\\s*(string|number|boolean|any)\\b"),
        Pattern.compile("\\bas\\s+(string|number|boolean|any|unknown|const)\\b")
    );

    /**
     * One engine shared by every run. Creating an engine is the expensive part of GraalJS startup;
     * a context off a warm engine is cheap, and contexts stay fully isolated from one another.
     */
    private static final class EngineHolder {
        private static final Engine INSTANCE = Engine.newBuilder()
            // A stock JVM has no Graal compiler, so Truffle would log a performance warning on every
            // single run. Interpreted is the expected mode here and the warning is just noise.
            .option("engine.WarnInterpreterOnly", "false")
            .build();
    }

    /** Watchdog threads for cancelling runaway scripts. One is plenty; cancellation is rare and brief. */
    private static final ScheduledExecutorService WATCHDOG =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hytale-mcp-script-watchdog");
            thread.setDaemon(true);
            return thread;
        });

    public record Options(long timeoutMillis, int maxCells, long seed) {
        public static Options defaults() {
            return new Options(10_000, 300_000, 0);
        }
    }

    public record Result(BuildRecorder recorder, List<String> log) {
    }

    private final BlockCatalog catalog;

    public ScriptRunner(BlockCatalog catalog) {
        this.catalog = catalog;
    }

    public Result run(String code, Options options) {
        rejectTypeScript(code);

        BuildRecorder recorder = new BuildRecorder(options.maxCells());
        BuildApi api = new BuildApi(catalog, recorder, options.seed());

        Context context = Context.newBuilder("js")
            .engine(EngineHolder.INSTANCE)
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup(name -> false)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .allowCreateProcess(false)
            .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
            .allowIO(org.graalvm.polyglot.io.IOAccess.NONE)
            .build();

        // close(true) is what actually stops a spinning script; an interrupt cannot.
        ScheduledFuture<?> deadline = WATCHDOG.schedule(
            () -> closeQuietly(context, true), options.timeoutMillis(), TimeUnit.MILLISECONDS);

        boolean cancelled = false;
        try {
            api.install(context.getBindings("js"));
            // Evaluated exactly as submitted, with nothing prepended, so the line numbers in any error
            // match the script the agent actually wrote.
            context.eval(Source.newBuilder("js", code, SOURCE_NAME).buildLiteral());
        } catch (PolyglotException e) {
            cancelled = e.isCancelled() || e.isInterrupted();
            throw translate(e, code, options.timeoutMillis());
        } catch (ScriptError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ScriptError(ScriptError.Phase.RUNTIME,
                e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } finally {
            deadline.cancel(false);
            // A cancelled context is already closing on the watchdog thread; closing again from here
            // would race with it.
            if (!cancelled) closeQuietly(context, false);
        }

        failOnUnknownNames(api.unknownNames());

        if (recorder.isEmpty()) {
            throw new ScriptError(ScriptError.Phase.VALIDATE,
                "The script placed no blocks. Call block(), box(), line(), sphere(), cylinder() or "
                    + "ellipsoid() to place something.");
        }
        return new Result(recorder, api.log());
    }

    private static void closeQuietly(Context context, boolean cancelIfExecuting) {
        try {
            context.close(cancelIfExecuting);
        } catch (RuntimeException ignored) {
            // Already closed, or closing from the other side; nothing useful left to do.
        }
    }

    private void failOnUnknownNames(Map<String, Integer> unknown) {
        if (unknown.isEmpty()) return;
        List<ScriptError.UnknownName> details = new ArrayList<>();
        // Cap the report: a loop over a bad name list produces hundreds of distinct misses, and the
        // first handful already tell the agent what it got wrong.
        int shown = 0;
        for (String name : unknown.keySet()) {
            if (shown++ >= 10) break;
            details.add(new ScriptError.UnknownName(name, catalog.suggest(name, 3)));
        }
        throw ScriptError.unknownNames(details);
    }

    private static void rejectTypeScript(String code) {
        for (Pattern p : TYPESCRIPT_MARKERS) {
            if (p.matcher(code).find()) {
                throw new ScriptError(ScriptError.Phase.TYPESCRIPT,
                    "This tool evaluates JavaScript, not TypeScript. Remove the type annotations, "
                        + "interface/type/enum declarations and 'as' casts, and resubmit as plain JS.");
            }
        }
    }

    /** Maps whatever the engine threw onto a structured error, keeping line, column and stack. */
    private static ScriptError translate(PolyglotException e, String code, long timeoutMillis) {
        if (e.isCancelled() || e.isInterrupted()) {
            return new ScriptError(ScriptError.Phase.TIMEOUT,
                "Script exceeded its " + timeoutMillis + "ms budget. "
                    + "Check for an unbounded loop, or build something smaller.");
        }

        // A ScriptError raised by one of the API helpers already says exactly what went wrong, so it
        // is passed through with the engine's position information attached rather than reworded.
        if (e.isHostException()) {
            Throwable host = e.asHostException();
            if (host instanceof ScriptError se) {
                return reposition(se, e, code);
            }
            String message = host.getMessage();
            return build(ScriptError.Phase.RUNTIME,
                host.getClass().getSimpleName() + (message == null ? "" : ": " + message), e, code);
        }

        if (e.isSyntaxError()) {
            return build(ScriptError.Phase.PARSE, cleanMessage(e), e, code);
        }
        if (e.isResourceExhausted()) {
            return build(ScriptError.Phase.LIMIT, cleanMessage(e), e, code);
        }
        return build(ScriptError.Phase.RUNTIME, cleanMessage(e), e, code);
    }

    private static ScriptError build(ScriptError.Phase phase, String message,
                                     PolyglotException e, String code) {
        int line = lineOf(e);
        return new ScriptError(phase, message, line, columnOf(e),
            ScriptError.extractSourceLine(code, line), stackOf(e), List.of());
    }

    private static ScriptError reposition(ScriptError original, PolyglotException e, String code) {
        int line = lineOf(e);
        return new ScriptError(original.phase(), original.getMessage(), line, columnOf(e),
            ScriptError.extractSourceLine(code, line), stackOf(e), original.unknownNames());
    }

    private static int lineOf(PolyglotException e) {
        var location = e.getSourceLocation();
        if (location != null && location.hasLines()) return location.getStartLine();
        // A host exception carries no source location of its own, so fall back to the innermost guest
        // frame, which is the call site inside the script.
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (frame.isGuestFrame() && frame.getSourceLocation() != null
                && frame.getSourceLocation().hasLines()) {
                return frame.getSourceLocation().getStartLine();
            }
        }
        return 0;
    }

    private static int columnOf(PolyglotException e) {
        var location = e.getSourceLocation();
        if (location != null && location.hasColumns()) return location.getStartColumn();
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (frame.isGuestFrame() && frame.getSourceLocation() != null
                && frame.getSourceLocation().hasColumns()) {
                return frame.getSourceLocation().getStartColumn();
            }
        }
        return 0;
    }

    /** Guest frames only: the host half of the trace is this plugin's internals and means nothing to the agent. */
    @Nullable
    private static String stackOf(PolyglotException e) {
        StringBuilder out = new StringBuilder();
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame()) continue;
            if (out.length() > 0) out.append('\n');
            out.append("  at ").append(frame.toString());
            if (out.length() > 2000) break;
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static String cleanMessage(PolyglotException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.toString();
        // GraalJS prefixes syntax errors with the source coordinates, which are reported separately.
        int marker = message.indexOf(SOURCE_NAME + ":");
        if (marker >= 0) {
            int colon = message.indexOf(' ', marker);
            if (colon > 0) return message.substring(colon + 1).trim();
        }
        return message;
    }
}

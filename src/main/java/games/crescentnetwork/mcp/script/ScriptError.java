package games.crescentnetwork.mcp.script;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A build failure, shaped so the agent that caused it can repair it without guessing.
 *
 * <p>Every field beyond {@code message} is optional, but the ones that matter most in practice are
 * {@link #line} and {@link #sourceLine}: a model correcting its own code repairs far more reliably
 * when it can see the offending line echoed back than when it only gets a stack trace.
 */
public final class ScriptError extends RuntimeException {

    /** Where in the pipeline the failure happened. Kept distinct so retries are not wasted. */
    public enum Phase {
        /** TypeScript syntax was submitted; this plugin evaluates JavaScript only. */
        TYPESCRIPT,
        /** The script did not parse. */
        PARSE,
        /** The script threw while running. */
        RUNTIME,
        /** The script ran past its time budget, or looped forever. */
        TIMEOUT,
        /** A resource cap was hit: too many blocks, or a primitive too large to expand. */
        LIMIT,
        /** The script ran, but what it produced cannot be written as a prefab. */
        VALIDATE,
        /** The prefab could not be written to disk. */
        SAVE
    }

    /** A name that matched nothing in either registry, with the closest things that do exist. */
    public record UnknownName(String name, List<String> suggestions) {
    }

    private final Phase phase;
    private final int line;
    private final int column;
    @Nullable
    private final String sourceLine;
    @Nullable
    private final String scriptStack;
    private final List<UnknownName> unknownNames;

    public ScriptError(Phase phase, String message) {
        this(phase, message, 0, 0, null, null, List.of());
    }

    public ScriptError(Phase phase,
                       String message,
                       int line,
                       int column,
                       @Nullable String sourceLine,
                       @Nullable String scriptStack,
                       List<UnknownName> unknownNames) {
        super(message);
        this.phase = phase;
        this.line = line;
        this.column = column;
        this.sourceLine = sourceLine;
        this.scriptStack = scriptStack;
        this.unknownNames = List.copyOf(unknownNames);
    }

    public Phase phase() {
        return phase;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    @Nullable
    public String sourceLine() {
        return sourceLine;
    }

    @Nullable
    public String scriptStack() {
        return scriptStack;
    }

    public List<UnknownName> unknownNames() {
        return unknownNames;
    }

    /** Pulls the 1-based {@code line} out of the script text, for echoing back with the error. */
    @Nullable
    public static String extractSourceLine(@Nullable String source, int line) {
        if (source == null || line <= 0) return null;
        String[] lines = source.split("\n", -1);
        if (line > lines.length) return null;
        String text = lines[line - 1];
        // Long generated lines are common; a truncated line is still far better than none.
        if (text.length() > 400) text = text.substring(0, 400) + " ...";
        return text.stripTrailing();
    }

    public static ScriptError unknownNames(List<UnknownName> unknown) {
        List<String> parts = new ArrayList<>();
        for (UnknownName u : unknown) {
            if (u.suggestions().isEmpty()) {
                parts.add("\"" + u.name() + "\" (no close matches)");
            } else {
                parts.add("\"" + u.name() + "\" (did you mean " + String.join(", ", u.suggestions()) + "?)");
            }
        }
        String message = "Unknown block or fluid name: " + String.join("; ", parts)
            + ". Use search_blocks to find valid names.";
        return new ScriptError(Phase.VALIDATE, message, 0, 0, null, null, unknown);
    }
}

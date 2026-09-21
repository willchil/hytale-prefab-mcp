package games.crescentnetwork.mcp.mcp.tools;

import com.google.gson.JsonObject;
import games.crescentnetwork.mcp.http.JsonRpc;
import games.crescentnetwork.mcp.mcp.McpCaller;
import games.crescentnetwork.mcp.mcp.McpServices;
import games.crescentnetwork.mcp.mcp.McpTool;
import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.prefab.PrefabLocation;
import games.crescentnetwork.mcp.prefab.PrefabWriter;
import games.crescentnetwork.mcp.script.ScriptError;
import games.crescentnetwork.mcp.script.ScriptRunner;

import java.util.List;

/** Runs a build script and saves the result as a prefab. */
public final class BuildPrefabTool implements McpTool {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;
    private static final long MAX_TIMEOUT_MS = 60_000;
    private static final int MAX_CODE_CHARS = 600_000;

    private final McpServices services;

    public BuildPrefabTool(McpServices services) {
        this.services = services;
    }

    @Override
    public String name() {
        return "build_prefab";
    }

    @Override
    public String description() {
        return """
            Run a JavaScript build script on the server and save the result as a Hytale .prefab.json \
            in the server's prefabs/ directory.

            You write the script; this tool only executes it. Plan the structure yourself, then place \
            blocks with the functions below. Block names must be real names from this server's \
            palette, so use search_blocks first. After building, use render_prefab to look at what you \
            made and iterate.

            LANGUAGE: plain JavaScript only, not TypeScript. No modules, no async/await, no require, \
            no I/O. Math and the usual built-ins are available.

            PLACEMENT
              block(x, y, z, name [, opts])
              box(x1, y1, z1, x2, y2, z2, name [, opts])        filled; opts {hollow: true} for a shell
              line(x1, y1, z1, x2, y2, z2, name [, opts])
              sphere(cx, cy, cz, r, name [, opts])
              ellipsoid(cx, cy, cz, rx, ry, rz, name [, opts])
              cylinder(cx, cy, cz, r, h, name [, axis] [, opts])   axis is "x", "y" (default) or "z"

            FLUIDS: there is no separate fluid call. Pass a fluid name to any of the above and it goes \
            into the prefab's fluid layer, so box(...,"Water_Source") fills a lake. Blocks and fluids \
            are separate layers, so calling block() twice at one coordinate, once with a block and \
            once with a fluid, gives you a waterlogged cell such as seaweed underwater. Fluid level is \
            inferred from the name (a *_Source name is a full source cell); override with \
            opts {level: 1-8}.

            OPTIONS: opts {rotation: n} for blocks, opts {level: n} for fluids, opts {hollow: true} \
            for box/sphere/ellipsoid/cylinder.

            QUERY AND EDIT
              blockAt(x, y, z) / fluidAt(x, y, z)   name placed there, or null
              clear(x, y, z)                        remove from both layers
              mirrorX(planeX) / mirrorY(planeY) / mirrorZ(planeZ)   duplicate everything across a plane
              findBlocks({query, group, drawType, kind, limit})     array of names, same index as search_blocks
              nearestBlock("#8a8a8a")               closest block by average colour
              setAnchor(x, y, z)                    prefab anchor, default 0,0,0
              rng()                                 seeded 0-1 random, reproducible for a given seed
              log(message)                          returned with the result, for your own debugging

            COORDINATES: Y is vertical and must be between -512 and 511. Later writes to the same \
            coordinate replace earlier ones. Only cells you place are written, so pasting the prefab \
            overlays terrain instead of clearing a box around the build.""";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = Schema.object();
        Schema.prop(schema, "name", "string",
            "Prefab file name, without the .prefab.json suffix.");
        Schema.prop(schema, "code", "string",
            "The JavaScript build script.");
        JsonObject overwrite = Schema.prop(schema, "overwrite", "boolean",
            "Replace an existing prefab of the same name.");
        Schema.defaultValue(overwrite, false);
        JsonObject seed = Schema.prop(schema, "seed", "integer",
            "Seed for rng(). The same seed reproduces the same build.");
        Schema.defaultValue(seed, 0);
        JsonObject timeout = Schema.prop(schema, "timeoutMs", "integer",
            "Script time budget in milliseconds (default 10000, max 60000).");
        Schema.defaultValue(timeout, (int) DEFAULT_TIMEOUT_MS);
        Schema.required(schema, "name", "code");
        return schema;
    }

    @Override
    public ToolResult call(JsonObject arguments, McpCaller caller) {
        BlockCatalog catalog = services.catalog();
        if (catalog == null) {
            return ToolResult.failure("The block palette is not loaded yet; the server is still booting.");
        }

        String name = JsonRpc.stringOr(arguments, "name", "").trim();
        String code = JsonRpc.stringOr(arguments, "code", "");
        if (name.isEmpty()) return ToolResult.failure("A prefab name is required.");
        if (code.isBlank()) return ToolResult.failure("No code was supplied.");
        if (code.length() > MAX_CODE_CHARS) {
            return ToolResult.failure("Script is " + code.length() + " characters, over the "
                + MAX_CODE_CHARS + " limit. Generate the detail with loops rather than by unrolling it.");
        }

        boolean overwrite = JsonRpc.booleanOr(arguments, "overwrite", false);
        long timeout = Math.max(250, Math.min(
            (long) JsonRpc.intOr(arguments, "timeoutMs", (int) DEFAULT_TIMEOUT_MS), MAX_TIMEOUT_MS));
        long seed = JsonRpc.intOr(arguments, "seed", 0);

        ScriptRunner runner = new ScriptRunner(catalog);
        ScriptRunner.Result result;
        try {
            result = runner.run(code, new ScriptRunner.Options(timeout, PrefabWriter.JSON_BLOCK_LIMIT, seed));
        } catch (ScriptError e) {
            return ToolResult.failure(format(e));
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.failure("Build failed unexpectedly: " + message);
        }

        String directory = PrefabLocation.directoryFor(caller);
        PrefabWriter.Saved saved;
        try {
            saved = PrefabWriter.write(result.recorder(), name, overwrite, directory);
        } catch (ScriptError e) {
            return ToolResult.failure(format(e));
        }

        // Kept so render_prefab can show this build straight back without re-reading it from disk.
        services.rememberBuild(directory, PrefabWriter.cleanName(name), result.recorder());

        StringBuilder out = new StringBuilder();
        out.append("Saved ").append(saved.path()).append('\n')
            .append("blocks: ").append(saved.blockCount())
            .append("  fluids: ").append(saved.fluidCount())
            .append("  distinct names: ").append(saved.distinctNames()).append('\n')
            .append("size: ").append(saved.width()).append(" x ").append(saved.height())
            .append(" x ").append(saved.length()).append(" (w x h x l)\n");

        if (!result.log().isEmpty()) {
            out.append("\nlog():\n");
            for (String line : result.log()) out.append("  ").append(line).append('\n');
        }
        out.append("\nUse render_prefab with name=\"").append(PrefabWriter.cleanName(name))
            .append("\" to see it.");
        return ToolResult.text(out.toString());
    }

    /** Renders a structured failure as the text the model will actually read and repair from. */
    static String format(ScriptError e) {
        StringBuilder out = new StringBuilder();
        out.append(switch (e.phase()) {
            case TYPESCRIPT -> "TypeScript rejected";
            case PARSE -> "Script did not parse";
            case RUNTIME -> "Script threw";
            case TIMEOUT -> "Script timed out";
            case LIMIT -> "Resource limit hit";
            case VALIDATE -> "Build is not valid";
            case SAVE -> "Could not save";
        });
        if (e.line() > 0) {
            out.append(" at line ").append(e.line());
            if (e.column() > 0) out.append(", column ").append(e.column());
        }
        out.append(":\n").append(e.getMessage()).append('\n');

        if (e.sourceLine() != null && !e.sourceLine().isBlank()) {
            out.append('\n').append(e.line()).append(" | ").append(e.sourceLine()).append('\n');
        }

        List<ScriptError.UnknownName> unknown = e.unknownNames();
        if (!unknown.isEmpty()) {
            out.append("\nUnresolved names:\n");
            for (ScriptError.UnknownName u : unknown) {
                out.append("  ").append(u.name()).append(" -> ");
                out.append(u.suggestions().isEmpty()
                    ? "no close match; search_blocks for a valid name"
                    : String.join(", ", u.suggestions()));
                out.append('\n');
            }
        }

        if (e.scriptStack() != null && !e.scriptStack().isBlank()) {
            out.append("\nStack:\n").append(e.scriptStack().stripTrailing()).append('\n');
        }
        return out.toString();
    }
}

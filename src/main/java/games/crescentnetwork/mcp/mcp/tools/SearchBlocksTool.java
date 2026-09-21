package games.crescentnetwork.mcp.mcp.tools;

import com.google.gson.JsonObject;
import games.crescentnetwork.mcp.http.JsonRpc;
import games.crescentnetwork.mcp.mcp.McpCaller;
import games.crescentnetwork.mcp.mcp.McpServices;
import games.crescentnetwork.mcp.mcp.McpTool;
import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.palette.BlockInfo;
import games.crescentnetwork.mcp.script.BuildApi;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/** Searches the live palette: every block and fluid the server currently has loaded. */
public final class SearchBlocksTool implements McpTool {

    private static final int DEFAULT_LIMIT = 40;
    private static final int MAX_LIMIT = 300;

    private final McpServices services;

    public SearchBlocksTool(McpServices services) {
        this.services = services;
    }

    @Override
    public String name() {
        return "search_blocks";
    }

    @Override
    public String description() {
        return """
            Search the Hytale server's live block and fluid palette. Returns canonical names, which are \
            the exact strings build_prefab expects.

            The palette is read from the running server's asset registry, so it includes blocks added \
            by other mods, not just the base game. Fluids (Water_Source, Lava_Source, ...) are listed \
            alongside blocks and are marked kind=fluid; build_prefab's block() accepts either.

            Search by text, filter by kind/group/pack/drawType, or pass color to rank by closest \
            average colour. drawType=Cube means a plain cubic block with real face textures; Model and \
            CubeWithModel blocks use a custom mesh.""";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = Schema.object();
        Schema.prop(schema, "query", "string",
            "Text matched against the block name, case-insensitive. Multiple words must all appear, "
                + "e.g. \"marble brick\".");
        JsonObject kind = Schema.prop(schema, "kind", "string", "Restrict to blocks or to fluids.");
        Schema.enumValues(kind, "block", "fluid");
        Schema.prop(schema, "group", "string", "Restrict to a block group, e.g. \"Stone\".");
        Schema.prop(schema, "pack", "string",
            "Restrict to one asset pack, e.g. \"Hytale:Hytale\" for the base game. Useful to find "
                + "blocks a mod added.");
        JsonObject drawType = Schema.prop(schema, "drawType", "string", "Restrict to a draw type.");
        Schema.enumValues(drawType, "Cube", "Model", "CubeWithModel", "Fluid");
        Schema.prop(schema, "color", "string",
            "Hex colour like \"#8a8a8a\". Results are ranked by nearest average block colour.");
        JsonObject limit = Schema.prop(schema, "limit", "integer", "Maximum results (default 40, max 300).");
        Schema.defaultValue(limit, DEFAULT_LIMIT);
        return schema;
    }

    @Override
    public ToolResult call(JsonObject arguments, McpCaller caller) {
        BlockCatalog catalog = services.catalog();
        if (catalog == null) {
            return ToolResult.failure("The block palette is not loaded yet; the server is still booting.");
        }

        String colorText = JsonRpc.stringOr(arguments, "color", null);
        Integer nearRgb = colorText == null ? null : BuildApi.parseHex(colorText);
        if (colorText != null && nearRgb == null) {
            return ToolResult.failure("color must look like \"#8a8a8a\".");
        }

        int limit = Math.max(1, Math.min(JsonRpc.intOr(arguments, "limit", DEFAULT_LIMIT), MAX_LIMIT));
        BlockCatalog.Query query = new BlockCatalog.Query(
            JsonRpc.stringOr(arguments, "query", null),
            parseKind(JsonRpc.stringOr(arguments, "kind", null)),
            JsonRpc.stringOr(arguments, "group", null),
            JsonRpc.stringOr(arguments, "pack", null),
            JsonRpc.stringOr(arguments, "drawType", null),
            nearRgb,
            limit);

        List<BlockInfo> hits = catalog.matching(query);
        if (hits.isEmpty()) {
            return ToolResult.text("No blocks matched. The palette holds " + catalog.size()
                + " entries; try a broader query or drop the filters.");
        }

        StringBuilder out = new StringBuilder();
        out.append("Matched ").append(hits.size());
        if (hits.size() == limit) out.append(" (limit reached)");
        out.append(" of ").append(catalog.size()).append(" palette entries.\n\n");
        out.append("name  |  kind  |  group  |  drawType  |  colour  |  pack\n");
        for (BlockInfo e : hits) {
            out.append(e.id())
                .append("  |  ").append(e.isFluid() ? "fluid" : "block")
                .append("  |  ").append(orDash(e.group()))
                .append("  |  ").append(orDash(e.drawType()))
                .append("  |  ").append(String.format("#%06x", e.rgb()))
                .append("  |  ").append(orDash(e.pack()))
                .append('\n');
        }
        return ToolResult.text(out.toString());
    }

    private static String orDash(@Nullable String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    @Nullable
    private static BlockInfo.Kind parseKind(@Nullable String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "fluid", "fluids" -> BlockInfo.Kind.FLUID;
            case "block", "blocks" -> BlockInfo.Kind.BLOCK;
            default -> null;
        };
    }
}

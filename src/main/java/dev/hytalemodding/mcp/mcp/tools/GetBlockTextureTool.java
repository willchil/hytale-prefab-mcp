package dev.hytalemodding.mcp.mcp.tools;

import com.google.gson.JsonObject;
import dev.hytalemodding.mcp.http.JsonRpc;
import dev.hytalemodding.mcp.mcp.McpServices;
import dev.hytalemodding.mcp.mcp.McpTool;
import dev.hytalemodding.mcp.palette.AssetLocator;
import dev.hytalemodding.mcp.palette.BlockCatalog;
import dev.hytalemodding.mcp.palette.BlockInfo;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * Returns an image of a block.
 *
 * <p>Cubic blocks have real per-face textures, so a named face can be returned directly. Most blocks
 * do not: about 1,784 of them are drawn from a custom model, and for those the only flat image that
 * exists is the pre-baked 64x64 inventory thumbnail the client renders offline. Hytale has no
 * server-side model renderer and no thumbnail atlas, so the thumbnail is the answer for those.
 */
public final class GetBlockTextureTool implements McpTool {

    private final McpServices services;

    public GetBlockTextureTool(McpServices services) {
        this.services = services;
    }

    @Override
    public String name() {
        return "get_block_texture";
    }

    @Override
    public String description() {
        return """
            Get an image of a block so you can see what it actually looks like before building with it.

            For a cubic block (drawType=Cube) this returns that block's real face texture, and you can \
            ask for a specific face. For a block drawn from a custom model, and for anything else \
            without face textures, it returns the block's inventory icon instead, which is a small \
            pre-rendered thumbnail of the whole model.

            Use search_blocks first to find valid names.""";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = Schema.object();
        Schema.prop(schema, "name", "string",
            "Block name, e.g. \"Rock_Marble_Brick_Smooth\". Case-insensitive.");
        JsonObject face = Schema.prop(schema, "face", "string",
            "Which face to return, for cubic blocks only. Ignored when the block has no face textures.");
        Schema.enumValues(face, "up", "down", "north", "south", "east", "west");
        Schema.defaultValue(face, "south");
        Schema.required(schema, "name");
        return schema;
    }

    @Override
    public ToolResult call(JsonObject arguments) {
        BlockCatalog catalog = services.catalog();
        if (catalog == null) {
            return ToolResult.failure("The block palette is not loaded yet; the server is still booting.");
        }

        String name = JsonRpc.stringOr(arguments, "name", "").trim();
        if (name.isEmpty()) {
            return ToolResult.failure("A block name is required.");
        }

        BlockInfo info = catalog.find(name);
        if (info == null) {
            List<String> suggestions = catalog.suggest(name, 5);
            return ToolResult.failure("No block or fluid named \"" + name + "\"."
                + (suggestions.isEmpty() ? "" : " Did you mean: " + String.join(", ", suggestions) + "?"));
        }

        String faceName = JsonRpc.stringOr(arguments, "face", "south");
        List<String> faces = info.faceTextures();
        if (!faces.isEmpty()) {
            int index = faceIndex(faceName);
            if (index < faces.size()) {
                byte[] png = AssetLocator.readCommonAsset(faces.get(index));
                if (png != null) {
                    return ToolResult.image(png, "image/png",
                        info.id() + " (" + faceName.toLowerCase(Locale.ROOT) + " face texture, drawType="
                            + orDash(info.drawType()) + ", average colour " + hex(info.rgb()) + ")");
                }
            }
        }

        if (info.iconPath() != null) {
            byte[] png = AssetLocator.readCommonAsset(info.iconPath());
            if (png != null) {
                String why = info.cubic()
                    ? "face texture unavailable, showing inventory icon"
                    : "drawn from a custom model, so this is its inventory icon";
                return ToolResult.image(png, "image/png",
                    info.id() + " (" + why + ", drawType=" + orDash(info.drawType())
                        + ", average colour " + hex(info.rgb()) + ")");
            }
        }

        // Some fluids and a handful of blocks genuinely ship no flat image. The average colour is
        // still worth returning: it is what the renderer uses, and it is enough to reason about.
        return ToolResult.text(info.id() + " has no face texture or icon in the loaded asset packs."
            + " drawType=" + orDash(info.drawType())
            + ", kind=" + (info.isFluid() ? "fluid" : "block")
            + ", average colour " + hex(info.rgb()) + ".");
    }

    private static String hex(int rgb) {
        return String.format("#%06x", rgb);
    }

    private static String orDash(@Nullable String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static int faceIndex(String face) {
        return switch (face.trim().toLowerCase(Locale.ROOT)) {
            case "up", "top" -> BlockInfo.UP;
            case "down", "bottom" -> BlockInfo.DOWN;
            case "north" -> BlockInfo.NORTH;
            case "east" -> BlockInfo.EAST;
            case "west" -> BlockInfo.WEST;
            default -> BlockInfo.SOUTH;
        };
    }
}

package games.crescentnetwork.mcp.mcp.tools;

import com.google.gson.JsonObject;
import games.crescentnetwork.mcp.http.JsonRpc;
import games.crescentnetwork.mcp.mcp.McpCaller;
import games.crescentnetwork.mcp.mcp.McpServices;
import games.crescentnetwork.mcp.mcp.McpTool;
import games.crescentnetwork.mcp.palette.AssetLocator;
import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.palette.BlockInfo;
import games.crescentnetwork.mcp.palette.Orientation;
import games.crescentnetwork.mcp.render.model.BakedModel;
import games.crescentnetwork.mcp.render.model.ModelLibrary;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * Returns an image of a block, with the facts about its shape that a picture does not convey.
 *
 * <p>Cubic blocks have real per-face textures, so a named face can be returned directly. Most blocks
 * do not: about 1,300 of them are drawn from a custom model, and for those the only flat image that
 * exists is the pre-baked 64x64 inventory thumbnail the client renders offline. Hytale has no
 * thumbnail atlas, so the thumbnail is the answer for those.
 *
 * <p>Every answer also carries the block's size: the cells it fills at each rotation it supports,
 * and for a model the real extent of its geometry. A thumbnail cannot say that a shallow roof is two
 * cells long or that a pole is an eighth of a block wide, and those are exactly the facts an agent
 * needs to lay blocks out without overlaps or gaps.
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
            Get an image of a block so you can see what it actually looks like before building with it, \
            together with its size and the rotations it supports.

            For a cubic block (drawType=Cube) this returns that block's real face texture, and you can \
            ask for a specific face. For a block drawn from a custom model, and for anything else \
            without face textures, it returns the block's inventory icon instead, which is a small \
            pre-rendered thumbnail of the whole model.

            The text alongside the image lists:
            - which cells the block fills at each supported rotation, as offsets from the cell you \
            place it at. Multi-cell blocks (shallow roofs, beds, doors) fill neighbouring cells that \
            must be left empty.
            - the yaw and pitch values build_prefab accepts for it.
            - for a model, how far its geometry really reaches, so you can tell a thin pole or a low \
            slab from a full block. A full cube spans 0..1 on each axis.

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
    public ToolResult call(JsonObject arguments, McpCaller caller) {
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
                            + orDash(info.drawType()) + ", average colour " + hex(info.rgb()) + ")"
                            + details(info, services.models()));
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
                        + ", average colour " + hex(info.rgb()) + ")"
                        + details(info, services.models()));
            }
        }

        // Some fluids and a handful of blocks genuinely ship no flat image. The average colour is
        // still worth returning: it is what the renderer uses, and it is enough to reason about.
        return ToolResult.text(info.id() + " has no face texture or icon in the loaded asset packs."
            + " drawType=" + orDash(info.drawType())
            + ", kind=" + (info.isFluid() ? "fluid" : "block")
            + ", average colour " + hex(info.rgb()) + "."
            + details(info, services.models()));
    }

    /**
     * Size, footprint and rotations, as lines to append to a caption.
     *
     * @param models where to read the model from for its real extent, or null to leave that out
     */
    static String details(BlockInfo info, @Nullable ModelLibrary models) {
        StringBuilder out = new StringBuilder("\n");
        if (info.isFluid()) {
            return out.append("size: 1 cell. Fluids share cells with blocks, so one can fill the same ")
                .append("cell as a plant or a multi-cell block.").toString();
        }

        BlockInfo.Geometry geometry = info.geometry();
        List<Integer> rotations = geometry.rotations();
        if (!geometry.isMultiCell()) {
            out.append("size: 1 cell.");
        } else {
            out.append("size: ").append(geometry.footprint(0).size()).append(" cells at yaw 0");
            if (geometry.hitboxType() != null) out.append(" (hitbox ").append(geometry.hitboxType()).append(')');
            out.append(". It fills these cells, as offsets from the cell you place it at; ")
                .append("nothing else may go in them:");
            for (int r : rotations) {
                BlockInfo.Footprint f = geometry.footprint(r);
                out.append("\n  ").append(Orientation.label(r)).append(": ").append(Orientation.offsets(f))
                    .append(" (").append(Orientation.reach(f)).append(')');
            }
        }

        out.append("\nrotations: ").append(Orientation.describe(rotations))
            .append(". Pass them to build_prefab as opts {yaw, pitch} in degrees.");

        BlockInfo.ModelRef ref = geometry.model();
        if (ref != null && models != null) {
            BakedModel model = models.baked(ref, 0, info.rgb());
            if (model != null) {
                double[] b = model.bounds();
                out.append("\nmodel extent at yaw 0, in blocks from the placed cell's lower corner ")
                    .append("(a full cube is 0..1 on each axis): x ").append(span(b[0], b[3]))
                    .append(", y ").append(span(b[1], b[4]))
                    .append(", z ").append(span(b[2], b[5]))
                    .append(". Geometry reaching a little past the footprint, like a roof's overhang, is ")
                    .append("only visual.");
            }
        }
        return out.toString();
    }

    private static String span(double lo, double hi) {
        return String.format(Locale.ROOT, "%.2f..%.2f", lo, hi);
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

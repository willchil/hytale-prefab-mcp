package games.crescentnetwork.mcp.palette;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One searchable palette entry, flattened out of the live asset registries so the MCP tools never
 * touch {@code BlockType} / {@code Fluid} directly while serving a request.
 *
 * <p>Blocks and fluids share this type on purpose. They live in two separate registries and end up in
 * two separate arrays in the prefab file, but an agent picks materials by name and should not have to
 * care which side of that split a name falls on. There are no id collisions between the two — checked
 * across all 2,952 block ids and all 14 fluid ids, case-insensitively — so {@link #id} alone is enough
 * to route a name back to the registry it came from.
 */
public record BlockInfo(
    String id,
    Kind kind,
    @Nullable String group,
    @Nullable String pack,
    @Nullable String drawType,
    boolean cubic,
    int rgb,
    @Nullable String iconPath,
    /** Face textures in UP, DOWN, NORTH, SOUTH, EAST, WEST order; empty unless the block is cubic. */
    List<String> faceTextures,
    int defaultFluidLevel
) {
    /** Index into {@link #faceTextures}. */
    public static final int UP = 0, DOWN = 1, NORTH = 2, SOUTH = 3, EAST = 4, WEST = 5;

    public enum Kind { BLOCK, FLUID }

    /** Sentinel used when an asset supplies no colour; a mid grey reads acceptably in a render. */
    public static final int FALLBACK_RGB = 0x808080;

    public boolean isFluid() {
        return kind == Kind.FLUID;
    }

    public String lowerId() {
        return id.toLowerCase(java.util.Locale.ROOT);
    }
}

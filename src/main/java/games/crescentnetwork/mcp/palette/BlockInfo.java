package games.crescentnetwork.mcp.palette;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockFlipType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation;

import javax.annotation.Nullable;
import java.util.ArrayList;
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
    int defaultFluidLevel,
    Geometry geometry
) {
    /** Index into {@link #faceTextures}. */
    public static final int UP = 0, DOWN = 1, NORTH = 2, SOUTH = 3, EAST = 4, WEST = 5;

    public enum Kind { BLOCK, FLUID }

    /** Sentinel used when an asset supplies no colour; a mid grey reads acceptably in a render. */
    public static final int FALLBACK_RGB = 0x808080;

    /** A plain single-cell block with no model and no rotations, which is what every fluid is. */
    public BlockInfo(String id, Kind kind, @Nullable String group, @Nullable String pack,
                     @Nullable String drawType, boolean cubic, int rgb, @Nullable String iconPath,
                     List<String> faceTextures, int defaultFluidLevel) {
        this(id, kind, group, pack, drawType, cubic, rgb, iconPath, faceTextures, defaultFluidLevel,
            Geometry.CUBE);
    }

    public boolean isFluid() {
        return kind == Kind.FLUID;
    }

    public String lowerId() {
        return id.toLowerCase(java.util.Locale.ROOT);
    }

    /** The custom model a block is drawn with, as {@code Common/}-relative paths. */
    public record ModelRef(String modelPath, @Nullable String texturePath, float scale) {
    }

    /**
     * Cells a block fills, as inclusive offsets from the cell it was placed at.
     *
     * <p>Hytale takes this from the block's hitbox, not from its model: a roof's model overhangs its
     * cell a little, but only the hitbox decides which neighbouring cells become filler.
     */
    public record Footprint(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public static final Footprint SINGLE = new Footprint(0, 0, 0, 0, 0, 0);

        public int width() {
            return maxX - minX + 1;
        }

        public int height() {
            return maxY - minY + 1;
        }

        public int length() {
            return maxZ - minZ + 1;
        }

        public boolean isSingle() {
            return width() == 1 && height() == 1 && length() == 1;
        }

        /** {@code WxHxL}, or {@code 1} for an ordinary single-cell block. */
        public String size() {
            return isSingle() ? "1" : width() + "x" + height() + "x" + length();
        }
    }

    /**
     * Shape and orientation facts about a block, beyond what it looks like.
     *
     * @param model        custom model, or null for a plain cube
     * @param hitboxType   hitbox asset id, or null when the block has none
     * @param footprints   footprint per rotation index; empty when the block fills one cell at every rotation
     * @param rotationMask bit {@code i} set when rotation index {@code i} (yaw and pitch only) is allowed;
     *                     bit 0 is always set
     * @param flipType     how the block's rotation changes when a build is mirrored
     * @param variantRotation the asset's rotation family, used to canonicalise a mirrored rotation
     */
    public record Geometry(
        @Nullable ModelRef model,
        @Nullable String hitboxType,
        List<Footprint> footprints,
        int rotationMask,
        @Nullable BlockFlipType flipType,
        @Nullable VariantRotation variantRotation
    ) {
        public static final Geometry CUBE = new Geometry(null, null, List.of(), 1, null, null);

        public Geometry {
            footprints = footprints == null ? List.of() : List.copyOf(footprints);
            rotationMask |= 1;
        }

        public Footprint footprint(int rotation) {
            if (footprints.isEmpty() || rotation < 0 || rotation >= footprints.size()) return Footprint.SINGLE;
            return footprints.get(rotation);
        }

        public boolean isMultiCell() {
            return !footprints.isEmpty();
        }

        public boolean allows(int rotation) {
            return rotation >= 0 && rotation < Orientation.COUNT && (rotationMask & (1 << rotation)) != 0;
        }

        /** Allowed rotation indices in ascending order, always starting with 0. */
        public List<Integer> rotations() {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < Orientation.COUNT; i++) {
                if ((rotationMask & (1 << i)) != 0) out.add(i);
            }
            return out;
        }
    }
}

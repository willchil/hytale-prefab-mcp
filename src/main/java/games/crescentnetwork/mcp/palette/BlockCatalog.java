package games.crescentnetwork.mcp.palette;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.common.util.StringUtil;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockTypeTextures;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.CustomModelTexture;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An immutable snapshot of every placeable block and every fluid the server currently has loaded.
 *
 * <p>Taken from the live registries rather than from a checked-in list, so blocks contributed by other
 * mods are included automatically and a pack that overrides a base block wins here exactly as it wins
 * in game. Rebuilt on boot and on asset reload; serving a search off a snapshot keeps request handling
 * off the asset lock.
 */
public final class BlockCatalog {

    /** Registry entries that are machinery rather than materials, and only add noise to a search. */
    private static final List<String> HIDDEN_IDS = List.of("empty", "unknown", "debug_cube", "debug_model");

    private final List<BlockInfo> entries;
    private final Map<String, BlockInfo> byLowerId;
    /** Block states, which are never searched or placed by name but do turn up in saved prefabs. */
    private final Map<String, BlockInfo> statesByLowerId;

    private BlockCatalog(List<BlockInfo> entries, List<BlockInfo> states) {
        this.entries = List.copyOf(entries);
        this.byLowerId = indexById(entries);
        this.statesByLowerId = indexById(states);
    }

    private static Map<String, BlockInfo> indexById(List<BlockInfo> entries) {
        Map<String, BlockInfo> index = new HashMap<>(entries.size() * 2);
        for (BlockInfo e : entries) {
            index.put(e.lowerId(), e);
        }
        return Map.copyOf(index);
    }

    /**
     * Builds a catalog from an explicit list instead of from the registries, so the script engine and
     * the geometry helpers can be exercised without a booted server.
     */
    public static BlockCatalog of(List<BlockInfo> entries) {
        List<BlockInfo> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return new BlockCatalog(sorted, List.of());
    }

    /**
     * Reads both registries. Callers hold {@code AssetRegistry.ASSET_LOCK.readLock()} for the duration,
     * because the asset file watcher can swap assets underneath an iteration.
     */
    public static BlockCatalog snapshot() {
        List<BlockInfo> out = new ArrayList<>(3200);
        List<BlockInfo> states = new ArrayList<>(2000);
        collectBlocks(out, states);
        collectFluids(out);
        out.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return new BlockCatalog(out, states);
    }

    private static void collectBlocks(List<BlockInfo> out, List<BlockInfo> states) {
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        for (Map.Entry<String, BlockType> entry : map.getAssetMap().entrySet()) {
            String id = entry.getKey();
            BlockType block = entry.getValue();
            if (block == null || isHidden(id)) continue;

            Item item = block.getItem();
            DrawType drawType = block.getDrawType();
            BlockInfo info = new BlockInfo(
                block.getId(),
                BlockInfo.Kind.BLOCK,
                block.getGroup(),
                map.getAssetPack(id),
                drawType == null ? null : drawType.name(),
                block.isCubeDrawType(),
                toRgb(block.getTextureComputedColor(), block.getParticleColor()),
                item == null ? null : item.getIcon(),
                faceTexturesOf(block),
                0,
                geometryOf(block)
            );
            // A block state (Furniture_..._OpenDoorOut, a roof's corner piece) is reachable through
            // its parent rather than placed directly, and listing every state would bury the real
            // materials. States are still kept for rendering, since prefabs saved in game carry them.
            if (block.isState()) {
                states.add(info);
            } else {
                out.add(info);
            }
        }
    }

    /**
     * Model, footprint and allowed rotations for a block.
     *
     * <p>The footprint comes from the hitbox through {@link FillerBlockUtil#forEachFillerBlock}, the
     * same routine the server uses to place filler cells, so it cannot drift from what the game does.
     * It is worked out for every rotation up front: a hitbox rotates with its block, so a shallow roof
     * that reaches north at yaw 0 reaches west at yaw 90.
     */
    private static BlockInfo.Geometry geometryOf(BlockType block) {
        VariantRotation variants = block.getVariantRotation();
        return new BlockInfo.Geometry(modelOf(block), block.getHitboxType(), footprintsOf(hitboxOf(block)),
            rotationMaskOf(variants), block.getFlipType(), variants);
    }

    /**
     * A hitbox's footprint at each rotation, or an empty list when it never leaves its own cell.
     * Public so tests can derive footprints from a hand-built hitbox through the same code path.
     */
    public static List<BlockInfo.Footprint> footprintsOf(@Nullable BlockBoundingBoxes hitbox) {
        if (hitbox == null || !hitbox.protrudesUnitBox()) return List.of();
        List<BlockInfo.Footprint> all = new ArrayList<>(Orientation.COUNT);
        for (int r = 0; r < Orientation.COUNT; r++) {
            all.add(footprintOf(hitbox.get(r)));
        }
        return all;
    }

    /** The yaw and pitch rotations an asset family allows, as a {@link BlockInfo.Geometry} mask. */
    public static int rotationMaskOf(@Nullable VariantRotation variants) {
        int mask = 1;
        if (variants == null) return mask;
        for (RotationTuple r : variants.getRotations()) {
            // Roll is not offered to scripts; see Orientation.
            if (r != null && r.index() < Orientation.COUNT) mask |= 1 << r.index();
        }
        return mask;
    }

    @Nullable
    private static BlockInfo.ModelRef modelOf(BlockType block) {
        DrawType drawType = block.getDrawType();
        if (drawType != DrawType.Model && drawType != DrawType.CubeWithModel) return null;
        String modelPath = block.getCustomModel();
        if (modelPath == null || modelPath.isBlank()) return null;
        // First weighted variant only, for the same reason as faceTexturesOf: a stable preview.
        CustomModelTexture[] textures = block.getCustomModelTexture();
        String texture = textures == null || textures.length == 0 || textures[0] == null
            ? null : textures[0].getTexture();
        float scale = block.getCustomModelScale();
        return new BlockInfo.ModelRef(modelPath, texture, scale > 0 ? scale : 1f);
    }

    @Nullable
    private static BlockBoundingBoxes hitboxOf(BlockType block) {
        try {
            return BlockBoundingBoxes.getAssetMap().getAsset(block.getHitboxTypeIndex());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BlockInfo.Footprint footprintOf(@Nullable BlockBoundingBoxes.RotatedVariantBoxes boxes) {
        if (boxes == null) return BlockInfo.Footprint.SINGLE;
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        FillerBlockUtil.forEachFillerBlock(boxes, (x, y, z) -> {
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.min(b[2], z);
            b[3] = Math.max(b[3], x);
            b[4] = Math.max(b[4], y);
            b[5] = Math.max(b[5], z);
        });
        if (b[0] == Integer.MAX_VALUE) return BlockInfo.Footprint.SINGLE;
        return new BlockInfo.Footprint(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static void collectFluids(List<BlockInfo> out) {
        var map = Fluid.getAssetMap();
        for (Map.Entry<String, Fluid> entry : map.getAssetMap().entrySet()) {
            String id = entry.getKey();
            Fluid fluid = entry.getValue();
            if (fluid == null || isHidden(id)) continue;
            List<String> textures = fluidTextures(fluid.getId());
            out.add(new BlockInfo(
                fluid.getId(),
                BlockInfo.Kind.FLUID,
                "Fluid",
                map.getAssetPack(id),
                "Fluid",
                !textures.isEmpty(),
                fluidRgb(fluid.getParticleColor(), textures),
                null,
                textures,
                defaultLevelFor(fluid.getId(), fluid.getMaxFluidLevel())
            ));
        }
    }

    /**
     * Face textures for a cubic block, as {@code Common/}-relative PNG paths.
     *
     * <p>Only the first weighted variant is taken. A block can ship several for random variation, but
     * a preview render does not need that and picking one keeps the result stable between renders.
     */
    private static List<String> faceTexturesOf(BlockType block) {
        if (!block.isCubeDrawType()) return List.of();
        BlockTypeTextures[] variants = block.getTextures();
        if (variants == null || variants.length == 0 || variants[0] == null) return List.of();
        BlockTypeTextures t = variants[0];
        String up = t.getUp(), down = t.getDown(), north = t.getNorth();
        String south = t.getSouth(), east = t.getEast(), west = t.getWest();
        if (up == null || down == null || north == null || south == null || east == null || west == null) {
            return List.of();
        }
        return List.of(up, down, north, south, east, west);
    }

    /**
     * The texture a fluid renders with.
     *
     * <p>{@code Fluid} exposes no texture accessor, so the path is reconstructed from the shipped
     * naming convention: every fluid texture is {@code BlockTextures/Fluid_<base>.png}, where the base
     * drops the {@code _Source} or {@code _Finite} suffix that distinguishes a source cell from
     * flowing remnants. Verified against all 14 fluid assets and the 6 textures they share. A fluid
     * whose texture is missing simply renders untextured.
     */
    private static List<String> fluidTextures(String fluidId) {
        String base = fluidId;
        for (String suffix : List.of("_Source", "_Finite")) {
            if (base.length() > suffix.length()
                && base.regionMatches(true, base.length() - suffix.length(), suffix, 0, suffix.length())) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        String path = "BlockTextures/Fluid_" + base + ".png";
        if (AssetLocator.locateCommonAsset(path) == null) return List.of();
        return List.of(path, path, path, path, path, path);
    }

    private static int fluidRgb(@Nullable Color particleColor, List<String> textures) {
        if (particleColor != null) return toRgb(particleColor, null);
        if (textures.isEmpty()) return BlockInfo.FALLBACK_RGB;

        int averaged = AssetLocator.averageRgb(textures.get(0));
        if (averaged < 0) return BlockInfo.FALLBACK_RGB;

        // Water ships a near-white texture and declares its FluidFX fog as EnvironmentTint, so its
        // colour only exists once a biome tints it. Multiplying by the commonest WaterTint recovers
        // roughly what a player sees; without it water renders bare grey.
        int tint = commonWaterTint();
        if (tint < 0) return averaged;
        return multiply(averaged, tint);
    }

    /**
     * The most frequently declared {@code WaterTint} across loaded environments, so this follows the
     * assets rather than a baked-in blue and picks up a mod's tint if one dominates.
     */
    private static int commonWaterTint() {
        try {
            Map<Integer, Integer> counts = new HashMap<>();
            for (Environment environment : Environment.getAssetMap().getAssetMap().values()) {
                if (environment == null) continue;
                Color tint = environment.getWaterTint();
                if (tint == null) continue;
                counts.merge(toRgb(tint, null), 1, Integer::sum);
            }
            int best = -1;
            int bestCount = 0;
            for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    bestCount = entry.getValue();
                    best = entry.getKey();
                }
            }
            return best;
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    private static int multiply(int base, int tint) {
        int r = (((base >> 16) & 0xFF) * ((tint >> 16) & 0xFF)) / 255;
        int g = (((base >> 8) & 0xFF) * ((tint >> 8) & 0xFF)) / 255;
        int b = ((base & 0xFF) * (tint & 0xFF)) / 255;
        return (r << 16) | (g << 8) | b;
    }

    /**
     * The level a fluid gets when a script does not name one.
     *
     * <p>Derived from the shipped prefab corpus rather than guessed: across the 643 base-pack prefabs
     * that contain fluids, every one of the 154,143 {@code *_Source} cells is level 1 with no
     * exceptions, while the flowing names peak at the maximum level and tail off down a flow gradient
     * ({@code Water} is level 8 in 7,704 of its 8,102 cells). So a source is a full source cell, and
     * anything else defaults to a full flowing cell.
     */
    static int defaultLevelFor(String fluidId, int maxFluidLevel) {
        if (fluidId.toLowerCase(Locale.ROOT).endsWith("_source")) return 1;
        return maxFluidLevel > 0 ? maxFluidLevel : 8;
    }

    private static boolean isHidden(String id) {
        return HIDDEN_IDS.contains(id.toLowerCase(Locale.ROOT));
    }

    private static int toRgb(@Nullable Color primary, @Nullable Color fallback) {
        Color c = primary != null ? primary : fallback;
        if (c == null) return BlockInfo.FALLBACK_RGB;
        return ((c.red & 0xFF) << 16) | ((c.green & 0xFF) << 8) | (c.blue & 0xFF);
    }

    public int size() {
        return entries.size();
    }

    public List<BlockInfo> all() {
        return entries;
    }

    /** Names are case-insensitive in Hytale, so lookups here are too. */
    @Nullable
    public BlockInfo find(@Nullable String name) {
        if (name == null) return null;
        return byLowerId.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Like {@link #find}, but also resolves block states. Only the renderer wants this: a prefab saved
     * in game can hold a roof's corner state, which should draw as its model rather than as grey.
     */
    @Nullable
    public BlockInfo findForRender(@Nullable String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        BlockInfo info = byLowerId.get(key);
        return info != null ? info : statesByLowerId.get(key);
    }

    public boolean contains(String name) {
        return find(name) != null;
    }

    /**
     * Near-misses for a name that did not resolve, so a typo comes back as a correction rather than as
     * a bare failure. Searches blocks and fluids together for the same reason {@code block()} accepts
     * both: the agent does not know which registry it meant to hit.
     */
    public List<String> suggest(@Nullable String name, int limit) {
        if (name == null || name.isBlank() || limit <= 0) return List.of();
        List<String> ids = new ArrayList<>(entries.size());
        for (BlockInfo e : entries) ids.add(e.id());
        List<String> ranked = StringUtil.sortByFuzzyDistance(name.trim(), ids, limit);
        return ranked.size() > limit ? List.copyOf(ranked.subList(0, limit)) : ranked;
    }

    public List<BlockInfo> matching(Query query) {
        List<BlockInfo> hits = new ArrayList<>();
        for (BlockInfo e : entries) {
            if (query.accepts(e)) hits.add(e);
        }
        query.rank(hits);
        return hits.size() > query.limit() ? hits.subList(0, query.limit()) : hits;
    }

    /** Filter set for a palette search. Every field is optional; an empty query lists everything. */
    public record Query(
        @Nullable String text,
        @Nullable BlockInfo.Kind kind,
        @Nullable String group,
        @Nullable String pack,
        @Nullable String drawType,
        @Nullable Integer nearRgb,
        int limit
    ) {
        boolean accepts(BlockInfo e) {
            if (kind != null && e.kind() != kind) return false;
            if (group != null && !group.equalsIgnoreCase(e.group())) return false;
            if (pack != null && !pack.equalsIgnoreCase(e.pack())) return false;
            if (drawType != null && !drawType.equalsIgnoreCase(e.drawType())) return false;
            if (text != null && !text.isBlank()) {
                String haystack = e.lowerId();
                for (String token : text.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
                    if (!haystack.contains(token)) return false;
                }
            }
            return true;
        }

        void rank(List<BlockInfo> hits) {
            if (nearRgb != null) {
                hits.sort((a, b) -> Integer.compare(distance(a.rgb(), nearRgb), distance(b.rgb(), nearRgb)));
            } else if (text != null && !text.isBlank()) {
                // Shorter ids matching the same tokens are the more general material: a search for
                // "marble" should surface Rock_Marble before Rock_Marble_Brick_Ornate_Damaged.
                hits.sort((a, b) -> Integer.compare(a.id().length(), b.id().length()));
            }
        }

        /** Squared distance in RGB. Crude next to a perceptual space, but stable and good enough to rank. */
        private static int distance(int a, int b) {
            int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
            int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
            int db = (a & 0xFF) - (b & 0xFF);
            return dr * dr + dg * dg + db * db;
        }
    }
}

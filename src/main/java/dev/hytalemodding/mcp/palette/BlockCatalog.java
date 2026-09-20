package dev.hytalemodding.mcp.palette;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.common.util.StringUtil;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockTypeTextures;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;

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

    private BlockCatalog(List<BlockInfo> entries) {
        this.entries = List.copyOf(entries);
        Map<String, BlockInfo> index = new HashMap<>(entries.size() * 2);
        for (BlockInfo e : entries) {
            index.put(e.lowerId(), e);
        }
        this.byLowerId = Map.copyOf(index);
    }


    /**
     * Builds a catalog from an explicit list instead of from the registries, so the script engine and
     * the geometry helpers can be exercised without a booted server.
     */
    public static BlockCatalog of(List<BlockInfo> entries) {
        List<BlockInfo> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return new BlockCatalog(sorted);
    }

    /**
     * Reads both registries. Callers hold {@code AssetRegistry.ASSET_LOCK.readLock()} for the duration,
     * because the asset file watcher can swap assets underneath an iteration.
     */
    public static BlockCatalog snapshot() {
        List<BlockInfo> out = new ArrayList<>(3200);
        collectBlocks(out);
        collectFluids(out);
        out.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return new BlockCatalog(out);
    }

    private static void collectBlocks(List<BlockInfo> out) {
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        for (Map.Entry<String, BlockType> entry : map.getAssetMap().entrySet()) {
            String id = entry.getKey();
            BlockType block = entry.getValue();
            if (block == null || isHidden(id)) continue;
            // A block state (Furniture_..._OpenDoorOut and friends) is reachable through its parent
            // rather than placed directly, and listing every state would bury the real materials.
            if (block.isState()) continue;

            Item item = block.getItem();
            DrawType drawType = block.getDrawType();
            out.add(new BlockInfo(
                block.getId(),
                BlockInfo.Kind.BLOCK,
                block.getGroup(),
                map.getAssetPack(id),
                drawType == null ? null : drawType.name(),
                block.isCubeDrawType(),
                toRgb(block.getTextureComputedColor(), block.getParticleColor()),
                item == null ? null : item.getIcon(),
                faceTexturesOf(block),
                0
            ));
        }
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

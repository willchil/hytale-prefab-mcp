package games.crescentnetwork.mcp.prefab;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.common.util.PathUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.blocktype.component.BlockPhysics;
import com.hypixel.hytale.server.core.prefab.PrefabFormat;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import games.crescentnetwork.mcp.script.BuildRecorder;
import games.crescentnetwork.mcp.script.ScriptError;
import org.joml.Vector3i;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Turns what a script placed into a {@code .prefab.json} in the server's {@code prefabs/} directory.
 *
 * <p>The file is never hand-encoded. A {@link BlockSelection} is handed to
 * {@link PrefabStore#savePrefab}, and the server writes it with its own serializer, which stamps the
 * format version and the {@code blockIdVersion} migration counter, sorts entries by {@code (x, z, y)}
 * for stable diffs, and omits optional fields sitting at their defaults. Producing the JSON here
 * instead would mean re-deriving all of that and drifting from it on the next server update.
 *
 * <p>Output is sparse: only cells the script actually placed are written, plus the filler cells that
 * multi-cell blocks need, so pasting overlays terrain rather than clearing the volume around the
 * build. Hytale's own paste UI has an air-override toggle for when the other behaviour is wanted.
 */
public final class PrefabWriter {

    /** {@link PrefabStore} writes binary {@code .lpf} at or above this, rather than JSON. */
    public static final int JSON_BLOCK_LIMIT = 300_000;

    public record Saved(
        Path path,
        int blockCount,
        /** Extra cells written so multi-cell blocks are whole; not counted in {@link #blockCount}. */
        int fillerCount,
        int fluidCount,
        int width,
        int height,
        int length,
        int distinctNames
    ) {
    }

    private PrefabWriter() {
    }

    /**
     * @param name      prefab file name, without the {@code .prefab.json} suffix
     * @param overwrite whether an existing prefab of that name may be replaced
     * @param directory where to write it, relative to the server prefabs directory
     */
    public static Saved write(BuildRecorder recorder, String name, boolean overwrite, String directory) {
        String cleaned = cleanName(name);
        BuildRecorder.Bounds bounds = recorder.bounds();

        BlockSelection selection = new BlockSelection(recorder.blockCount(), 0);
        selection.setAnchor(recorder.anchorX(), recorder.anchorY(), recorder.anchorZ());
        selection.setSelectionArea(
            new Vector3i(bounds.minX(), bounds.minY(), bounds.minZ()),
            new Vector3i(bounds.maxX(), bounds.maxY(), bounds.maxZ()));

        addBlocks(recorder, selection);
        int fillerCount = addFillers(selection);
        addFluids(recorder, selection);

        // Filler cells can reach past the placed cells, so the stored area is widened to hold them.
        int[] area = bounds(selection, bounds);
        selection.setSelectionArea(
            new Vector3i(area[0], area[1], area[2]),
            new Vector3i(area[3], area[4], area[5]));

        Path target = resolveTarget(cleaned, directory);
        Path written;
        try {
            // AUTO writes .prefab.json below the large-prefab threshold, which the cell cap keeps us
            // under, and .lpf above it.
            written = PrefabStore.get().savePrefab(target, selection, overwrite, PrefabFormat.AUTO);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (message.contains("ALREADY_EXISTS") || e.getClass().getSimpleName().equals("PrefabSaveException")) {
                throw new ScriptError(ScriptError.Phase.SAVE,
                    "Could not save prefab \"" + cleaned + "\": " + message
                        + (overwrite ? "" : ". Pass overwrite=true to replace an existing prefab."));
            }
            throw new ScriptError(ScriptError.Phase.SAVE, "Could not save prefab \"" + cleaned + "\": " + message);
        }

        return new Saved(written, recorder.blockCount(), fillerCount, recorder.fluidCount(),
            area[3] - area[0] + 1, area[4] - area[1] + 1, area[5] - area[2] + 1, recorder.namesUsed().size());
    }

    /**
     * Adds the filler cells every multi-cell block needs, so a bed or a shallow roof is whole when
     * the prefab is pasted.
     *
     * <p>Paste copies cells exactly as stored and never generates filler itself, so without this a
     * multi-cell block arrives as a lone base cell and Hytale's prefab validator reports it as having
     * missing filler. {@link BlockSelection#tryFixFiller} is the server's own generator, placing each
     * filler with its offset and its base block's rotation. It runs non-destructively: the script
     * layer has already rejected any build whose footprints collide, so it should never need to
     * overwrite anything, and if it ever did that would be a bug worth failing loudly on.
     *
     * @return how many filler cells were added
     */
    private static int addFillers(BlockSelection selection) {
        int before = selection.getBlockCount();
        try {
            selection.tryFixFiller(false);
        } catch (IllegalArgumentException e) {
            throw new ScriptError(ScriptError.Phase.SAVE,
                "A multi-cell block's filler cells collide with another block: " + e.getMessage());
        }
        return selection.getBlockCount() - before;
    }

    /** The bounding box of every stored cell, fillers included, starting from the placed cells' box. */
    private static int[] bounds(BlockSelection selection, BuildRecorder.Bounds placed) {
        int[] b = {placed.minX(), placed.minY(), placed.minZ(), placed.maxX(), placed.maxY(), placed.maxZ()};
        selection.forEachBlock((x, y, z, holder) -> {
            if (holder.filler() == FillerBlockUtil.NO_FILLER) return;
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.min(b[2], z);
            b[3] = Math.max(b[3], x);
            b[4] = Math.max(b[4], y);
            b[5] = Math.max(b[5], z);
        });
        return b;
    }

    private static void addBlocks(BuildRecorder recorder, BlockSelection selection) {
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        for (Map.Entry<Long, BuildRecorder.Placement> entry : recorder.blocks().entrySet()) {
            long k = entry.getKey();
            BuildRecorder.Placement p = entry.getValue();
            int id = map.getIndex(p.name());
            if (id == AssetMapWithIndexes.NOT_FOUND) {
                // Names were checked against the catalog before this point, so reaching here means the
                // registry changed underneath the run. Failing is right: Hytale resolves an unknown
                // name to BlockType.UNKNOWN without complaining, which would be invisible in game.
                throw new ScriptError(ScriptError.Phase.SAVE,
                    "Block \"" + p.name() + "\" is no longer in the registry; assets may have reloaded "
                        + "mid-build. Re-run the build.");
            }
            selection.addBlockAtLocalPos(
                BuildRecorder.unpackX(k), BuildRecorder.unpackY(k), BuildRecorder.unpackZ(k),
                id,
                p.rotation() == 0 ? RotationTuple.NONE_INDEX : p.rotation(),
                FillerBlockUtil.NO_FILLER,
                BlockPhysics.NULL_SUPPORT);
        }
    }

    private static void addFluids(BuildRecorder recorder, BlockSelection selection) {
        if (recorder.fluids().isEmpty()) return;
        var map = Fluid.getAssetMap();
        for (Map.Entry<Long, BuildRecorder.FluidPlacement> entry : recorder.fluids().entrySet()) {
            long k = entry.getKey();
            BuildRecorder.FluidPlacement p = entry.getValue();
            int id = map.getIndex(p.name());
            if (id == AssetMapWithIndexes.NOT_FOUND) {
                throw new ScriptError(ScriptError.Phase.SAVE,
                    "Fluid \"" + p.name() + "\" is no longer in the registry; assets may have reloaded "
                        + "mid-build. Re-run the build.");
            }
            selection.addFluidAtLocalPos(
                BuildRecorder.unpackX(k), BuildRecorder.unpackY(k), BuildRecorder.unpackZ(k),
                id, (byte) p.level());
        }
    }

    /**
     * Resolves a prefab path inside the server prefabs directory.
     *
     * <p>The subdirectory and the file name are resolved in two guarded steps, so neither a caller's
     * name nor a player's username can climb out of the prefabs directory. Nothing is created here;
     * {@code PrefabStore.savePrefab} makes the parent directories when it writes.
     */
    public static Path resolveTarget(String cleanedName, String directory) {
        Path base = PrefabStore.get().getServerPrefabsPath();
        Path dir = PathUtil.resolvePathWithinDir(base, directory);
        if (dir == null) {
            throw new ScriptError(ScriptError.Phase.SAVE,
                "Prefab directory \"" + directory + "\" escapes the prefabs directory.");
        }
        Path resolved = PathUtil.resolvePathWithinDir(dir, cleanedName);
        if (resolved == null) {
            throw new ScriptError(ScriptError.Phase.SAVE,
                "Prefab name \"" + cleanedName + "\" escapes the prefabs directory.");
        }
        return resolved;
    }

    /**
     * Strips any suffix the caller supplied and rejects anything that is not a plain file name.
     * {@code PrefabStore} adds the real suffix back when it decides the on-disk format.
     */
    public static String cleanName(String raw) {
        if (raw == null) {
            throw new ScriptError(ScriptError.Phase.SAVE, "A prefab name is required.");
        }
        String name = raw.trim().replace('\\', '/');
        while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);

        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".prefab.json")) {
            name = name.substring(0, name.length() - ".prefab.json".length());
        } else if (lower.endsWith(".lpf")) {
            name = name.substring(0, name.length() - ".lpf".length());
        } else if (lower.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }

        name = name.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new ScriptError(ScriptError.Phase.SAVE, "A prefab name is required.");
        }
        return name;
    }
}

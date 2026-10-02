package willchil.mcp.prefab;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import willchil.mcp.mcp.McpCaller;
import willchil.mcp.mcp.McpServices;
import willchil.mcp.script.BuildRecorder;
import willchil.mcp.script.ScriptError;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a saved prefab back into the same in-memory form a freshly run script produces, so the
 * renderer has one input shape regardless of whether a build was just made or is being revisited.
 */
public final class PrefabLoader {

    /** How many candidates an ambiguous name lists before summarising the rest. */
    private static final int MAX_LISTED = 10;

    private PrefabLoader() {
    }

    /** Loads a prefab by name from {@code directory}. */
    public static BuildRecorder load(String name, Path directory) {
        String cleaned = PrefabWriter.cleanName(name);
        return loadTarget(cleaned, PrefabWriter.resolveTarget(cleaned, directory));
    }

    /**
     * Loads the prefab at {@code target}.
     *
     * @param cleaned the name to report in errors
     * @param target the prefab's path without its suffix, as {@code PrefabStore} reads it
     */
    public static BuildRecorder loadTarget(String cleaned, Path target) {
        BlockSelection selection;
        try {
            selection = PrefabStore.get().getPrefab(target);
        } catch (RuntimeException e) {
            if (!exists(target)) {
                throw new ScriptError(ScriptError.Phase.VALIDATE,
                    "No prefab named \"" + cleaned + "\".");
            }
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ScriptError(ScriptError.Phase.VALIDATE,
                "Could not read prefab \"" + cleaned + "\": " + message);
        }
        if (selection == null) {
            throw new ScriptError(ScriptError.Phase.VALIDATE,
                "No prefab named \"" + cleaned + "\".");
        }
        return toRecorder(selection);
    }

    /**
     * Finds a prefab the caller can reach by name, or returns null when there is none.
     *
     * <p>A name with folders in it, such as {@code Trees/Oak_1}, is a path inside the server's
     * {@code prefabs/} directory and is looked up there alone. A plain name is looked for in the
     * caller's own staging (a build from this session is already in memory), then the caller's own
     * published folder, then anywhere under {@code prefabs/}, so prefabs from other players, the
     * in-game editor or an operator can be used too. Staging stays private to its caller.
     *
     * @throws ScriptError when a plain name matches more than one prefab under {@code prefabs/}
     */
    @Nullable
    public static BuildRecorder find(String name, McpServices services, McpCaller caller) {
        String cleaned = PrefabWriter.cleanName(name);
        Path prefabsRoot = PrefabStore.get().getServerPrefabsPath();
        if (PrefabLocation.hasFolder(name)) {
            Path target = PrefabLocation.resolveNested(prefabsRoot, name);
            return exists(target) ? loadTarget(cleaned, target) : null;
        }

        Path staging = PrefabLocation.stagingDirectory(services.stagingRoot(), caller);
        BuildRecorder cached = services.recentBuild(staging.toString(), cleaned);
        if (cached != null) return cached;
        if (exists(cleaned, staging)) return load(cleaned, staging);

        Path published = PrefabLocation.publishedDirectory(caller);
        if (exists(cleaned, published)) return load(cleaned, published);

        List<Path> matches = PrefabLocation.findInTree(prefabsRoot, cleaned);
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return loadTarget(cleaned, matches.get(0));

        StringBuilder out = new StringBuilder("More than one prefab is named \"" + cleaned
            + "\". Pass its path inside prefabs/ instead, one of:");
        int shown = 0;
        for (Path match : matches) {
            if (shown++ == MAX_LISTED) {
                out.append("\n  ... and ").append(matches.size() - MAX_LISTED).append(" more");
                break;
            }
            out.append("\n  ").append(prefabsRoot.relativize(match).toString().replace('\\', '/'));
        }
        throw new ScriptError(ScriptError.Phase.VALIDATE, out.toString());
    }

    /** Whether a prefab of that name is on disk in {@code directory}. */
    public static boolean exists(String name, Path directory) {
        return exists(PrefabWriter.resolveTarget(PrefabWriter.cleanName(name), directory));
    }

    private static boolean exists(Path target) {
        try {
            return PrefabStore.get().prefabExists(target);
        } catch (RuntimeException e) {
            return Files.exists(target);
        }
    }

    public static BuildRecorder toRecorder(BlockSelection selection) {
        // The cap only bounds what a script may create; an existing prefab is read whole.
        BuildRecorder recorder = new BuildRecorder(Integer.MAX_VALUE);
        recorder.setAnchor(selection.getAnchorX(), selection.getAnchorY(), selection.getAnchorZ());

        BlockTypeAssetMap<String, BlockType> blockMap = BlockType.getAssetMap();
        selection.forEachBlock((x, y, z, holder) -> {
            BlockType type = blockMap.getAsset(holder.blockId());
            if (type == null) return;
            String id = type.getId();
            // Prefabs captured from the world are dense and carry Empty for air. Those cells are
            // dropped here so a loaded build renders as its solid geometry rather than a filled box.
            if (BlockType.EMPTY_KEY.equalsIgnoreCase(id)) return;
            // Filler cells belong to a multi-cell block placed elsewhere. The renderer draws that
            // block's whole model from its base cell, and saving regenerates fillers, so keeping them
            // would draw the model once per cell it covers.
            if (holder.filler() != FillerBlockUtil.NO_FILLER) return;
            recorder.putBlock(x, y, z, id, holder.rotation());
        });

        var fluidMap = Fluid.getAssetMap();
        selection.forEachFluid((x, y, z, fluidId, level) -> {
            Fluid fluid = fluidMap.getAsset(fluidId);
            if (fluid == null) return;
            String id = fluid.getId();
            if (Fluid.EMPTY_KEY.equalsIgnoreCase(id)) return;
            recorder.putFluid(x, y, z, id, level);
        });

        return recorder;
    }
}

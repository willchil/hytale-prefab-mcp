package games.crescentnetwork.mcp.prefab;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import games.crescentnetwork.mcp.script.BuildRecorder;
import games.crescentnetwork.mcp.script.ScriptError;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a saved prefab back into the same in-memory form a freshly run script produces, so the
 * renderer has one input shape regardless of whether a build was just made or is being revisited.
 */
public final class PrefabLoader {

    private PrefabLoader() {
    }

    /** Loads a prefab from the server {@code prefabs/} directory by name. */
    public static BuildRecorder load(String name) {
        String cleaned = PrefabWriter.cleanName(name);
        Path target = PrefabWriter.resolveTarget(cleaned);

        BlockSelection selection;
        try {
            selection = PrefabStore.get().getPrefab(target);
        } catch (RuntimeException e) {
            if (!exists(target)) {
                throw new ScriptError(ScriptError.Phase.VALIDATE,
                    "No prefab named \"" + cleaned + "\" in the server prefabs directory.");
            }
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ScriptError(ScriptError.Phase.VALIDATE,
                "Could not read prefab \"" + cleaned + "\": " + message);
        }
        if (selection == null) {
            throw new ScriptError(ScriptError.Phase.VALIDATE,
                "No prefab named \"" + cleaned + "\" in the server prefabs directory.");
        }
        return toRecorder(selection);
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

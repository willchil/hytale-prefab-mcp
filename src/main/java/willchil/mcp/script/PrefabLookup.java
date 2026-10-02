package willchil.mcp.script;

import javax.annotation.Nullable;

/**
 * Finds an existing prefab by name, for a script that pastes one into its own build.
 *
 * <p>An interface rather than a direct call into the prefab store, so the script layer stays runnable
 * without a server: tests and previews pass {@link #NONE}.
 */
@FunctionalInterface
public interface PrefabLookup {

    /** For a run with no prefabs to draw on; every name is reported as not found. */
    PrefabLookup NONE = name -> null;

    /**
     * @return the prefab as placed cells with its anchor, or null when there is no prefab by that name
     * @throws ScriptError when a prefab exists but cannot be read
     */
    @Nullable
    BuildRecorder find(String name);
}

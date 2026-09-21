package games.crescentnetwork.mcp.mcp;

import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.render.TextureCache;
import games.crescentnetwork.mcp.render.model.ModelLibrary;
import games.crescentnetwork.mcp.script.BuildRecorder;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared state the tools read: the palette snapshot, decoded textures and models, and recently built
 * prefabs.
 */
public final class McpServices {

    /**
     * How many builds stay in memory for rendering. Rendering re-reads from disk when a build has
     * aged out, so this is only a shortcut, and a handful covers the build-look-adjust loop.
     */
    private static final int RECENT_BUILD_LIMIT = 4;

    private final AtomicReference<BlockCatalog> catalog = new AtomicReference<>();
    private final TextureCache textures = new TextureCache();
    private final ModelLibrary models = new ModelLibrary(textures);

    /** Access is synchronised on the map itself; tools run on the HTTP pool's threads. */
    private final Map<String, BuildRecorder> recentBuilds =
        new LinkedHashMap<>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BuildRecorder> eldest) {
                return size() > RECENT_BUILD_LIMIT;
            }
        };

    /** Last build per directory, guarded by the same lock as the cache it describes. */
    private final Map<String, String> lastBuildNames = new java.util.HashMap<>();

    @Nullable
    public BlockCatalog catalog() {
        return catalog.get();
    }

    public void setCatalog(BlockCatalog snapshot) {
        catalog.set(snapshot);
        // Assets may have been reskinned or remodelled, so anything decoded before can be stale.
        textures.clear();
        models.clear();
    }

    public TextureCache textures() {
        return textures;
    }

    public ModelLibrary models() {
        return models;
    }

    /**
     * Keeps a just-built prefab around so a render does not have to read it back from disk.
     *
     * <p>Keyed by directory as well as name. Two players can each have a prefab called "tower", and
     * a cache keyed on the name alone would hand one of them the other's build.
     */
    public void rememberBuild(String directory, String name, BuildRecorder recorder) {
        synchronized (recentBuilds) {
            recentBuilds.put(key(directory, name), recorder);
            lastBuildNames.put(directory, name);
        }
    }

    @Nullable
    public BuildRecorder recentBuild(String directory, String name) {
        synchronized (recentBuilds) {
            return recentBuilds.get(key(directory, name));
        }
    }

    private static String key(String directory, String name) {
        return directory.toLowerCase(java.util.Locale.ROOT) + "/" + name.toLowerCase(java.util.Locale.ROOT);
    }

    @Nullable
    /** The last prefab built in that directory, so a render with no name means "what I just made". */
    public String lastBuildName(String directory) {
        synchronized (recentBuilds) {
            return lastBuildNames.get(directory);
        }
    }
}

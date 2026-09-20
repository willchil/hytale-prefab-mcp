package dev.hytalemodding.mcp.mcp;

import dev.hytalemodding.mcp.palette.BlockCatalog;
import dev.hytalemodding.mcp.render.TextureCache;
import dev.hytalemodding.mcp.script.BuildRecorder;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Shared state the tools read: the palette snapshot, decoded textures, and recently built prefabs. */
public final class McpServices {

    /**
     * How many builds stay in memory for rendering. Rendering re-reads from disk when a build has
     * aged out, so this is only a shortcut, and a handful covers the build-look-adjust loop.
     */
    private static final int RECENT_BUILD_LIMIT = 4;

    private final AtomicReference<BlockCatalog> catalog = new AtomicReference<>();
    private final TextureCache textures = new TextureCache();

    /** Access is synchronised on the map itself; tools run on the HTTP pool's threads. */
    private final Map<String, BuildRecorder> recentBuilds =
        new LinkedHashMap<>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BuildRecorder> eldest) {
                return size() > RECENT_BUILD_LIMIT;
            }
        };

    private volatile String lastBuildName;

    @Nullable
    public BlockCatalog catalog() {
        return catalog.get();
    }

    public void setCatalog(BlockCatalog snapshot) {
        catalog.set(snapshot);
        // Assets may have been reskinned, so previously decoded textures can be stale.
        textures.clear();
    }

    public TextureCache textures() {
        return textures;
    }

    public void rememberBuild(String name, BuildRecorder recorder) {
        synchronized (recentBuilds) {
            recentBuilds.put(name.toLowerCase(java.util.Locale.ROOT), recorder);
        }
        lastBuildName = name;
    }

    @Nullable
    public BuildRecorder recentBuild(String name) {
        synchronized (recentBuilds) {
            return recentBuilds.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Nullable
    public String lastBuildName() {
        return lastBuildName;
    }
}

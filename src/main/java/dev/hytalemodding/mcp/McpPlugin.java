package dev.hytalemodding.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.server.core.Options;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.hytalemodding.mcp.http.McpHttpServer;
import dev.hytalemodding.mcp.mcp.McpProtocol;
import dev.hytalemodding.mcp.mcp.McpServices;
import dev.hytalemodding.mcp.mcp.McpTool;
import dev.hytalemodding.mcp.mcp.tools.BuildPrefabTool;
import dev.hytalemodding.mcp.mcp.tools.GetBlockTextureTool;
import dev.hytalemodding.mcp.mcp.tools.RenderPrefabTool;
import dev.hytalemodding.mcp.mcp.tools.SearchBlocksTool;
import dev.hytalemodding.mcp.palette.BlockCatalog;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Hosts an MCP server so an agentic harness can author Hytale prefabs against this server's own live
 * block palette.
 */
public final class McpPlugin extends JavaPlugin {

    private static final String PORT_PROPERTY = "hytale.mcp.port";
    private static final String CONFIG_FILE = "mcp.json";
    /**
     * Preferred port. Fixed rather than derived, because a singleplayer world is launched with an
     * ephemeral {@code --bind} port that changes every time, so anything derived from it would send
     * the agent to a different address on each launch.
     */
    private static final int DEFAULT_PORT = 8765;
    /**
     * Offset from the game port, used only when the preferred port is already taken. That is what
     * keeps several dedicated instances running side by side from colliding.
     */
    private static final int PORT_OFFSET = 2000;

    private final McpServices services = new McpServices();

    private McpHttpServer httpServer;

    public McpPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        List<McpTool> tools = List.of(
            new SearchBlocksTool(services),
            new GetBlockTextureTool(services),
            new BuildPrefabTool(services),
            new RenderPrefabTool(services));
        McpProtocol protocol = new McpProtocol(tools);
        this.httpServer = new McpHttpServer(protocol);

        // Assets are loaded by the time BootEvent fires, so the palette can be read then and not before.
        getEventRegistry().register(BootEvent.class, event -> onBoot());

        // Hot reloading a pack changes what is placeable, so the snapshot is retaken. Registered for
        // both registries because a pack can add either.
        getEventRegistry().register(LoadedAssetsEvent.class, BlockType.class, this::onBlockAssetsLoaded);
        getEventRegistry().register(LoadedAssetsEvent.class, Fluid.class, this::onFluidAssetsLoaded);

        getEventRegistry().register((short) -36, ShutdownEvent.class, event -> shutdownServer());
    }

    @Override
    protected void shutdown() {
        shutdownServer();
    }

    private void onBoot() {
        refreshCatalog(false);

        if (!isMcpEnabled()) {
            getLogger().at(Level.INFO).log("MCP server disabled by %s", CONFIG_FILE);
            return;
        }

        for (int port : candidatePorts()) {
            if (httpServer.start(port)) {
                BlockCatalog catalog = services.catalog();
                getLogger().at(Level.INFO).log(
                    "MCP server listening on %s (%d palette entries). Connect with: claude mcp add --transport http hytale-prefab %s",
                    httpServer.url(), catalog == null ? 0 : catalog.size(), httpServer.url());
                return;
            }
        }
        getLogger().at(Level.WARNING).log(
            "MCP server could not bind any candidate port. Set \"port\" in %s to choose one explicitly.",
            CONFIG_FILE);
    }

    // Declared with their full asset types rather than as lambdas: the keyed register() overload
    // infers its event type from the handler, and a lambda leaves it unresolved.
    private void onBlockAssetsLoaded(
        @Nonnull LoadedAssetsEvent<String, BlockType, BlockTypeAssetMap<String, BlockType>> event) {
        refreshCatalog(true);
    }

    private void onFluidAssetsLoaded(
        @Nonnull LoadedAssetsEvent<String, Fluid, IndexedLookupTableAssetMap<String, Fluid>> event) {
        refreshCatalog(true);
    }

    private void shutdownServer() {
        if (httpServer != null) {
            httpServer.stop();
        }
    }

    /**
     * Retakes the palette snapshot under the asset read lock, because the file watcher can swap assets
     * underneath an iteration.
     *
     * @param quiet true for a reload, where logging at INFO on every asset change would be noise
     */
    private void refreshCatalog(boolean quiet) {
        AssetRegistry.ASSET_LOCK.readLock().lock();
        try {
            BlockCatalog snapshot = BlockCatalog.snapshot();
            services.setCatalog(snapshot);
            if (!quiet) {
                getLogger().at(Level.INFO).log("Palette snapshot: %d blocks and fluids", snapshot.size());
            }
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).log("Failed to snapshot the block palette: %s", e.toString());
        } finally {
            AssetRegistry.ASSET_LOCK.readLock().unlock();
        }
    }

    /**
     * Ports to try, in order.
     *
     * <p>An explicit choice, from the {@code hytale.mcp.port} system property or from
     * {@code mcp.json}, is used on its own: if the port someone asked for is unavailable, silently
     * landing somewhere else would be worse than failing, because the agent is configured against
     * that exact address.
     *
     * <p>Otherwise the fixed default comes first so the address is stable across launches, and the
     * bind-derived port is the fallback that keeps several servers on one machine apart.
     */
    private List<Integer> candidatePorts() {
        Integer explicit = explicitPort();
        if (explicit != null) return List.of(explicit);

        List<Integer> candidates = new ArrayList<>(3);
        candidates.add(DEFAULT_PORT);
        Integer derived = bindDerivedPort();
        if (derived != null && !candidates.contains(derived)) candidates.add(derived);
        // 0 lets the OS pick anything free, so the endpoint still comes up and the log says where.
        candidates.add(0);
        return candidates;
    }

    @Nullable
    private Integer explicitPort() {
        String property = System.getProperty(PORT_PROPERTY);
        if (property != null && !property.isBlank()) {
            try {
                return Integer.parseInt(property.trim());
            } catch (NumberFormatException e) {
                getLogger().at(Level.WARNING).log("Ignoring invalid -D%s=%s", PORT_PROPERTY, property);
            }
        }
        JsonObject config = readConfig();
        if (config != null && config.has("port")) {
            try {
                return config.get("port").getAsInt();
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).log("Ignoring invalid \"port\" in %s", CONFIG_FILE);
            }
        }
        return null;
    }

    @Nullable
    private Integer bindDerivedPort() {
        try {
            InetSocketAddress bind = Options.getOptionSet().valueOf(Options.BIND);
            if (bind != null && bind.getPort() > 0) return bind.getPort() + PORT_OFFSET;
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).log("Could not read the game bind port: %s", e.toString());
        }
        return null;
    }

    private boolean isMcpEnabled() {
        JsonObject config = readConfig();
        if (config == null || !config.has("enabled")) return true;
        try {
            return config.get("enabled").getAsBoolean();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private JsonObject readConfig() {
        try {
            Path file = getDataDirectory().resolve(CONFIG_FILE);
            if (!Files.isRegularFile(file)) return null;
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("Could not read %s: %s", CONFIG_FILE, e.toString());
            return null;
        }
    }
}

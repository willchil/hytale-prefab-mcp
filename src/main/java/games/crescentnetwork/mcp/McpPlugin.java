package games.crescentnetwork.mcp;

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
import games.crescentnetwork.mcp.auth.McpAuthenticator;
import games.crescentnetwork.mcp.auth.McpTokens;
import games.crescentnetwork.mcp.command.PrefabMcpCommand;
import games.crescentnetwork.mcp.http.McpHttpServer;
import games.crescentnetwork.mcp.mcp.McpProtocol;
import games.crescentnetwork.mcp.mcp.McpServices;
import games.crescentnetwork.mcp.mcp.McpTool;
import games.crescentnetwork.mcp.mcp.tools.BuildPrefabTool;
import games.crescentnetwork.mcp.mcp.tools.GetBlockTextureTool;
import games.crescentnetwork.mcp.mcp.tools.RenderPrefabTool;
import games.crescentnetwork.mcp.mcp.tools.SearchBlocksTool;
import games.crescentnetwork.mcp.palette.BlockCatalog;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.logging.Level;

/**
 * Hosts an MCP server so an agentic harness can author Hytale prefabs against this server's own live
 * block palette.
 */
public final class McpPlugin extends JavaPlugin {

    private static final String PORT_PROPERTY = "hytale.mcp.port";
    /** Offset from the game port, so several servers on one machine do not collide by default. */
    private static final int PORT_OFFSET = 2000;
    /** Used only when the game's bind port cannot be read at all. */
    private static final int FALLBACK_PORT = 8765;

    private final McpServices services = new McpServices();

    private McpHttpServer httpServer;
    private McpConfig config;
    private McpTokens tokens;

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
        // Read here rather than at boot: the command and the authenticator both need the secret,
        // and both are built while the plugin is being set up.
        this.config = McpConfig.loadOrCreate(getDataDirectory(), defaultPort(),
            message -> getLogger().at(Level.WARNING).log("%s", message));
        this.tokens = new McpTokens(config.tokenSecret());

        McpAuthenticator authenticator =
            new McpAuthenticator(tokens, commandPermission(), config.requirePersonalToken());

        McpProtocol protocol = new McpProtocol(tools);
        this.httpServer = new McpHttpServer(protocol, authenticator);

        getCommandRegistry().registerCommand(new PrefabMcpCommand(httpServer, tokens, authenticator));
        // Logged because the node is derived, not written down anywhere an admin can read, and it is
        // the one thing they need in order to grant the command to someone.
        getLogger().at(Level.INFO).log("Registered /%s (permission: %s)",
            PrefabMcpCommand.NAME, commandPermission());

        // Assets are loaded by the time BootEvent fires, so the palette can be read then and not before.
        getEventRegistry().register(BootEvent.class, event -> onBoot());

        // Hot reloading a pack changes what is placeable, so the snapshot is retaken. Registered for
        // both registries because a pack can add either.
        getEventRegistry().register(LoadedAssetsEvent.class, BlockType.class, this::onBlockAssetsLoaded);
        getEventRegistry().register(LoadedAssetsEvent.class, Fluid.class, this::onFluidAssetsLoaded);

        getEventRegistry().register((short) -36, ShutdownEvent.class, event -> shutdownServer());
    }

    /**
     * The permission a sender needs to run {@code /prefab-mcp}.
     *
     * <p>Derived from the plugin's own base permission rather than written out, so it follows the
     * manifest's Group and Name instead of drifting from them. Resolves to
     * {@code games.crescentnetwork.prefabmcp.command}.
     *
     * <p>Not attached to any built-in permission group, so it has to be granted deliberately. The
     * {@code hytale:Admin} group holds a wildcard and therefore already has it, which is why a
     * singleplayer owner can run the command without configuring anything.
     */
    public String commandPermission() {
        return getBasePermission() + ".command";
    }

    @Override
    protected void shutdown() {
        shutdownServer();
    }

    private void onBoot() {
        refreshCatalog(false);

        // Stated plainly at boot: whether the endpoint is open is the thing an operator most needs
        // to know, and it is not obvious from anywhere else.
        if (config.requirePersonalToken()) {
            getLogger().at(Level.INFO).log(
                "Personal tokens required; every request needs the token of a player holding %s",
                commandPermission());
        } else {
            getLogger().at(Level.INFO).log(
                "Personal tokens NOT required: anyone who can reach the endpoint may use every tool. "
                    + "Set \"%s\": true in %s to require one.",
                McpConfig.REQUIRE_PERSONAL_TOKEN, McpConfig.FILE_NAME);
        }

        int port = portOverride() != null ? portOverride() : config.port();

        if (httpServer.start(port)) {
            BlockCatalog catalog = services.catalog();
            getLogger().at(Level.INFO).log(
                "MCP server listening on %s (%d palette entries). Connect with: claude mcp add --transport http hytale-prefab %s",
                httpServer.url(), catalog == null ? 0 : catalog.size(), httpServer.url());
        } else {
            // Not retried on another port on purpose: the agent is configured against this exact
            // address, so quietly moving would look like a working server nobody can reach.
            getLogger().at(Level.WARNING).log(
                "MCP server could not bind port %d. Change \"port\" in %s and restart.",
                port, McpConfig.fileIn(getDataDirectory()));
        }
    }

    /** The port written into a freshly created config: the game's own port plus an offset. */
    private int defaultPort() {
        try {
            InetSocketAddress bind = Options.getOptionSet().valueOf(Options.BIND);
            if (bind != null && bind.getPort() > 0) return bind.getPort() + PORT_OFFSET;
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).log("Could not read the game bind port: %s", e.toString());
        }
        return FALLBACK_PORT;
    }

    /** A system property wins over the file and is not written back to it. */
    @Nullable
    private Integer portOverride() {
        String property = System.getProperty(PORT_PROPERTY);
        if (property == null || property.isBlank()) return null;
        try {
            return Integer.parseInt(property.trim());
        } catch (NumberFormatException e) {
            getLogger().at(Level.WARNING).log("Ignoring invalid -D%s=%s", PORT_PROPERTY, property);
            return null;
        }
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



}

package games.crescentnetwork.mcp.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import games.crescentnetwork.mcp.McpConfig;
import games.crescentnetwork.mcp.http.McpHttpServer;
import games.crescentnetwork.mcp.mcp.McpProtocol;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.util.concurrent.CompletableFuture;

/**
 * Prints the MCP client configuration for this server.
 *
 * <p>The address depends on the port the listener actually took, which is not knowable up front: a
 * singleplayer world is launched on an ephemeral port, so the default derived from it differs per
 * machine and per install. Printing the live address beats documenting a number that will be wrong.
 */
public final class PrefabMcpCommand extends AbstractCommand {

    public static final String NAME = "prefab-mcp";

    // HTML escaping is off because this is a config file, not markup: with it on, the angle brackets
    // of the host placeholder come out as < and > and the block cannot be pasted as-is.
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /**
     * Stands in for the host in the printed configuration.
     *
     * <p>The port is known but the host is not: it depends on where the agent runs relative to this
     * server, and a reverse proxy or a DNS name is not something the server can see. Printing a
     * placeholder asks the operator for the one piece only they know, rather than guessing.
     */
    private static final String HOST_PLACEHOLDER = "<server-host>";

    private final McpHttpServer server;

    /**
     * @param permission the permission a sender must hold; see {@code McpPlugin.commandPermission()}
     */
    public PrefabMcpCommand(@Nonnull McpHttpServer server, @Nonnull String permission) {
        super(NAME, "Show the MCP client configuration for this server");
        this.server = server;
        // Must be declared before the command is registered.
        requirePermission(permission);
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (!server.isRunning()) {
            context.sendMessage(Message.raw(
                "The MCP server is not running. Check the server log for why it could not bind, "
                    + "then fix \"port\" in " + McpConfig.FILE_NAME + " and restart.").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }

        context.sendMessage(Message.raw("MCP server listening on port " + server.boundPort())
            .color(Color.GREEN));
        context.sendMessage(Message.raw("Add this to your MCP client configuration, replacing "
            + HOST_PLACEHOLDER + " with this server's address (127.0.0.1 if it is the same machine "
            + "as the agent):"));
        // One message per line so it arrives as lines rather than as a single wrapped blob, in a
        // player's chat as much as on the console.
        String url = server.urlFor(HOST_PLACEHOLDER);
        for (String line : GSON.toJson(configurationJson(url)).split("\n")) {
            context.sendMessage(Message.raw(line).color(Color.LIGHT_GRAY));
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * The {@code mcpServers} block a client expects.
     *
     * <p>Built with Gson rather than concatenated so the output is valid JSON whatever the address
     * turns out to be, and so it can be pasted without touching it.
     */
    static JsonObject configurationJson(String url) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "http");
        entry.addProperty("url", url);

        JsonObject servers = new JsonObject();
        servers.add(McpProtocol.SERVER_NAME, entry);

        JsonObject root = new JsonObject();
        root.add("mcpServers", servers);
        return root;
    }
}

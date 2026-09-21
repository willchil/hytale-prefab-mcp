package games.crescentnetwork.mcp.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import games.crescentnetwork.mcp.McpConfig;
import games.crescentnetwork.mcp.auth.McpTokens;
import games.crescentnetwork.mcp.http.McpHttpServer;
import games.crescentnetwork.mcp.mcp.McpProtocol;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Prints the MCP client configuration, including the caller's personal token.
 *
 * <p>This command is the only place a token is shown. It is derived rather than stored, so it can be
 * shown again at any time, and it is never logged or broadcast: it is sent only to the player who
 * asked, because holding it is what lets a client act as them.
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
    private final McpTokens tokens;
    private final String permission;

    public PrefabMcpCommand(@Nonnull McpHttpServer server, @Nonnull McpTokens tokens,
                            @Nonnull String permission) {
        super(NAME, "Show your personal MCP client configuration for this server");
        this.server = server;
        this.tokens = tokens;
        this.permission = permission;
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

        if (!context.isPlayer()) {
            // The console has no account, so there is no player for a token to authorise. Minting one
            // here would mean issuing a credential that answers to nobody.
            context.sendMessage(Message.raw(
                "Tokens are per player, so run this in game to get yours. Every request needs the "
                    + "token of a player holding " + permission + ".").color(Color.YELLOW));
            return CompletableFuture.completedFuture(null);
        }

        UUID player = context.sender().getUuid();
        String token = tokens.issue(player);

        context.sendMessage(Message.raw(
            "This token is personal to you. Anyone who has it can build on this server as you, so "
                + "do not share it or paste it anywhere public.").color(Color.YELLOW));
        context.sendMessage(Message.raw("Add this to your MCP client configuration, replacing "
            + HOST_PLACEHOLDER + " with this server's address (127.0.0.1 if it is the same machine "
            + "as the agent):"));
        // One message per line so it arrives as lines rather than as a single wrapped blob, in a
        // player's chat as much as on the console.
        for (String line : GSON.toJson(configurationJson(server.urlFor(HOST_PLACEHOLDER), token)).split("\n")) {
            context.sendMessage(Message.raw(line).color(Color.LIGHT_GRAY));
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * The {@code mcpServers} block a client expects.
     *
     * <p>Built with Gson rather than concatenated so the output is valid JSON whatever the address
     * and token turn out to be, and so it can be pasted without touching it.
     */
    static JsonObject configurationJson(String url, String token) {
        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer " + token);

        JsonObject entry = new JsonObject();
        entry.addProperty("type", "http");
        entry.addProperty("url", url);
        entry.add("headers", headers);

        JsonObject servers = new JsonObject();
        servers.add(McpProtocol.SERVER_NAME, entry);

        JsonObject root = new JsonObject();
        root.add("mcpServers", servers);
        return root;
    }
}

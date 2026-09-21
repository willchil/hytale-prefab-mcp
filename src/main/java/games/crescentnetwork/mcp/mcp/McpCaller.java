package games.crescentnetwork.mcp.mcp;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Who a tool call is running for.
 *
 * <p>Anonymous when the server does not require personal tokens, which is the default. Identified
 * when a token was presented and accepted, in which case {@link #playerName()} is the player's
 * username if they happened to be online when the request arrived, and null if they were not.
 */
public record McpCaller(@Nullable UUID player, @Nullable String playerName) {

    /** The caller on a server that does not require tokens. */
    public static final McpCaller ANONYMOUS = new McpCaller(null, null);

    public static McpCaller identified(UUID player, @Nullable String playerName) {
        return new McpCaller(player, playerName);
    }

    public boolean isIdentified() {
        return player != null;
    }
}

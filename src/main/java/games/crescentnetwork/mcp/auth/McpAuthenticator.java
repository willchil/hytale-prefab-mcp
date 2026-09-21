package games.crescentnetwork.mcp.auth;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Decides whether a request may use the MCP tools.
 *
 * <p>When personal tokens are required, two separate questions are asked in order: the token says
 * which player is calling, and the permission system says whether that player is allowed. Keeping
 * them apart means revoking the permission revokes MCP access immediately, without having to rotate
 * the secret and re-issue everyone's token.
 *
 * <p>The permission is resolved through groups and wildcards rather than looked for as a direct
 * grant, and resolves for a player who is not online, so a token keeps working between sessions.
 *
 * <p>When tokens are not required, every caller is allowed through anonymously and the header is not
 * read at all. The listener is bound to loopback, so reaching it already means being on the machine.
 */
public final class McpAuthenticator {

    /** Why a request was refused, phrased for the person reading it out of a client's error log. */
    public enum Failure {
        MISSING("No token. Add an Authorization: Bearer <token> header; run /prefab-mcp in game to get yours."),
        INVALID("Token is not valid for this server. It may have been mistyped, or the server's tokenSecret changed."),
        FORBIDDEN("That player does not have permission to use the MCP tools on this server.");

        private final String message;

        Failure(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /**
     * Whether the request may proceed, and who it is for.
     *
     * <p>{@code player} is null for an anonymous caller, which is a normal outcome when tokens are
     * not required, so being allowed is tracked separately from being identified.
     */
    public record Outcome(boolean allowed, @Nullable UUID player, @Nullable Failure failure) {
        static Outcome anonymous() {
            return new Outcome(true, null, null);
        }

        static Outcome of(UUID player) {
            return new Outcome(true, player, null);
        }

        static Outcome refused(Failure failure) {
            return new Outcome(false, null, failure);
        }
    }

    private final McpTokens tokens;
    private final String permission;
    private final boolean requireToken;

    public McpAuthenticator(McpTokens tokens, String permission, boolean requireToken) {
        this.tokens = tokens;
        this.permission = permission;
        this.requireToken = requireToken;
    }

    public String permission() {
        return permission;
    }

    /** Whether a caller must present a personal token, from {@code requirePersonalToken}. */
    public boolean requiresToken() {
        return requireToken;
    }

    /** @param authorizationHeader the raw {@code Authorization} header, or null if absent */
    public Outcome authenticate(@Nullable String authorizationHeader) {
        if (!requireToken) return Outcome.anonymous();

        String token = McpTokens.fromAuthorizationHeader(authorizationHeader);
        if (token == null) return Outcome.refused(Failure.MISSING);

        UUID player = tokens.verify(token);
        if (player == null) return Outcome.refused(Failure.INVALID);

        if (!hasPermission(player)) return Outcome.refused(Failure.FORBIDDEN);
        return Outcome.of(player);
    }

    private boolean hasPermission(UUID player) {
        try {
            return PermissionsModule.get().hasPermission(player, permission);
        } catch (RuntimeException | LinkageError e) {
            // Refuse rather than fall open: a permission system that cannot answer is not a reason
            // to let a caller run scripts on the server.
            return false;
        }
    }
}

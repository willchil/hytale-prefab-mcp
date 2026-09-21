package games.crescentnetwork.mcp.prefab;

import games.crescentnetwork.mcp.mcp.McpCaller;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Decides which directory under {@code prefabs/} a generated prefab belongs in.
 *
 * <p>Everything this plugin writes lives under one folder, so agent-made prefabs never mix with the
 * ones an operator or the in-game editor made. Within it, a server that requires personal tokens
 * knows who is calling and files each player's work separately.
 */
public final class PrefabLocation {

    /** Everything this plugin writes lives under here, inside the server's {@code prefabs/} directory. */
    public static final String ROOT = "prefab-mcp";

    /**
     * Where a prefab goes when the caller is a known player who was not online at the time.
     *
     * <p>A username can only be resolved for a player who is connected, and a token keeps working
     * between sessions, so this is the ordinary case for an agent running while nobody is logged in.
     */
    public static final String OFFLINE = "offline-players";

    private PrefabLocation() {
    }

    /**
     * The directory for a caller, relative to the server's {@code prefabs/} directory.
     *
     * <ul>
     *   <li>anonymous, on a server not requiring tokens: {@code prefab-mcp}</li>
     *   <li>identified and online: {@code prefab-mcp/<username>}</li>
     *   <li>identified but offline: {@code prefab-mcp/offline-players}</li>
     * </ul>
     */
    public static String directoryFor(McpCaller caller) {
        if (!caller.isIdentified()) return ROOT;

        String folder = sanitize(caller.playerName());
        return folder == null ? ROOT + "/" + OFFLINE : ROOT + "/" + folder;
    }

    /**
     * Reduces a username to something safe to use as one path segment.
     *
     * <p>A username reaching this point came from the server's own player list, but it becomes a
     * directory name, so anything that could change the meaning of a path is dropped rather than
     * trusted. Returns null when nothing usable is left.
     */
    @Nullable
    static String sanitize(@Nullable String username) {
        if (username == null) return null;

        // Trimmed before substituting, so a name of only whitespace falls back rather than becoming
        // a row of underscores.
        String trimmed = username.trim();
        if (trimmed.isEmpty()) return null;

        StringBuilder out = new StringBuilder(trimmed.length());
        for (char c : trimmed.toCharArray()) {
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            out.append(safe ? c : '_');
        }

        String cleaned = out.toString()
            // Runs of dots go first: a single dot is fine inside a name, but two in a row read as
            // the parent directory wherever they appear.
            .replaceAll("\\.{2,}", "_")
            .replaceAll("_{2,}", "_")
            .replaceAll("^[._]+", "")
            .replaceAll("[._]+$", "");
        if (cleaned.isEmpty()) return null;

        // Must not collide with the folder reserved for players whose name could not be resolved.
        if (cleaned.equalsIgnoreCase(OFFLINE)) return cleaned + "_";

        // Windows refuses these as directory names whatever the extension.
        String upper = cleaned.toUpperCase(Locale.ROOT);
        for (String reserved : new String[]{"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "LPT1", "LPT2"}) {
            if (upper.equals(reserved)) return cleaned + "_";
        }
        return cleaned;
    }
}

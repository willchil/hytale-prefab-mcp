package willchil.mcp.prefab;

import com.hypixel.hytale.common.util.PathUtil;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import willchil.mcp.McpConfig;
import willchil.mcp.mcp.McpCaller;
import willchil.mcp.script.ScriptError;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Decides which directories a generated prefab belongs in: the private staging area it is built in,
 * and the folder under {@code prefabs/} it is published to.
 *
 * <p>Everything this plugin publishes lives under one folder, so agent-made prefabs never mix with the
 * ones an operator or the in-game editor made. Within it, a server that requires personal tokens
 * knows who is calling and files each player's work separately. Staging is split the same way.
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
        String folder = subfolderFor(caller);
        return folder == null ? ROOT : ROOT + "/" + folder;
    }

    /**
     * The caller's own folder name, shared by the staging and published layouts: null for an
     * anonymous caller, the sanitised username for one who is online, {@link #OFFLINE} otherwise.
     */
    @Nullable
    public static String subfolderFor(McpCaller caller) {
        if (!caller.isIdentified()) return null;

        String folder = sanitize(caller.playerName());
        return folder == null ? OFFLINE : folder;
    }

    /** Where a caller's finished prefabs are published: {@link #directoryFor} inside the server prefabs directory. */
    public static Path publishedDirectory(McpCaller caller) {
        return within(PrefabStore.get().getServerPrefabsPath(), directoryFor(caller));
    }

    /**
     * Where a caller's work in progress is built, inside {@code stagingRoot}.
     *
     * <p>Staging sits outside {@code prefabs/} entirely, so nothing half-built ever shows up in the
     * in-game prefab browser.
     */
    public static Path stagingDirectory(Path stagingRoot, McpCaller caller) {
        String folder = subfolderFor(caller);
        return folder == null ? stagingRoot : within(stagingRoot, folder);
    }

    /**
     * Deletes the staging area and everything in it.
     *
     * <p>Run at shutdown, so unpublished work never outlives the session. Never throws: a file that
     * cannot be removed must not hold up the server stopping, so it is reported and skipped.
     */
    public static void clearStaging(Path stagingRoot, McpConfig.Problems onProblem) {
        if (!Files.exists(stagingRoot)) return;
        try (Stream<Path> walk = Files.walk(stagingRoot)) {
            // Deepest first, so each directory is empty by the time it is reached.
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    onProblem.report("Could not delete staged file " + path + ": " + e);
                }
            });
        } catch (IOException | RuntimeException e) {
            onProblem.report("Could not clear the prefab staging area " + stagingRoot + ": " + e);
        }
    }

    private static Path within(Path base, String relative) {
        Path resolved = PathUtil.resolvePathWithinDir(base, relative);
        if (resolved == null) {
            throw new ScriptError(ScriptError.Phase.SAVE,
                "Prefab directory \"" + relative + "\" escapes " + base.getFileName() + ".");
        }
        return resolved;
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

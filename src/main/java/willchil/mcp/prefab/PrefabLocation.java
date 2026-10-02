package willchil.mcp.prefab;

import com.hypixel.hytale.common.util.PathUtil;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import willchil.mcp.McpConfig;
import willchil.mcp.mcp.McpCaller;
import willchil.mcp.script.ScriptError;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Predicate;
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

    /** The suffixes {@code PrefabStore} saves under, lower case. */
    private static final String[] PREFAB_SUFFIXES = {".prefab.json", ".lpf"};

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

    /**
     * Every prefab anywhere under {@code root} whose file name matches {@code cleanedName}, ignoring
     * case and format.
     *
     * <p>Each result is the path without its suffix, the form {@code PrefabStore} reads, and a prefab
     * saved in both formats is listed once. Sorted, so an ambiguous name is reported the same way
     * every time. A directory that cannot be read is skipped rather than failing the whole search.
     */
    public static List<Path> findInTree(Path root, String cleanedName) {
        String wanted = cleanedName.toLowerCase(Locale.ROOT);
        return collect(root, Integer.MAX_VALUE, wanted::equals);
    }

    /**
     * Every prefab under {@code root}, in the same form as {@link #findInTree}.
     *
     * @param recursive false for only the prefabs directly in {@code root}, as for a staging or
     *     published directory that other callers' folders sit inside
     */
    public static List<Path> listInTree(Path root, boolean recursive) {
        return collect(root, recursive ? Integer.MAX_VALUE : 1, base -> true);
    }

    /** @param accept tested against each prefab's lower-case name without its suffix */
    private static List<Path> collect(Path root, int maxDepth, Predicate<String> accept) {
        if (!Files.isDirectory(root)) return List.of();
        TreeSet<Path> found = new TreeSet<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    String fileName = file.getFileName().toString();
                    String lower = fileName.toLowerCase(Locale.ROOT);
                    for (String suffix : PREFAB_SUFFIXES) {
                        if (lower.endsWith(suffix) && accept.test(lower.substring(0, lower.length() - suffix.length()))) {
                            found.add(file.resolveSibling(fileName.substring(0, fileName.length() - suffix.length())));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // The visitor swallows per-file failures, so this is the root itself becoming unreadable.
            return List.of();
        }
        return new ArrayList<>(found);
    }

    /**
     * Resolves a name that carries its own folders, such as {@code Trees/Oak_1}, against {@code root}.
     * The last segment is cleaned like any prefab name, so a suffix may be given or left off.
     *
     * @throws ScriptError when the path would leave {@code root}
     */
    public static Path resolveNested(Path root, String raw) {
        String path = raw.trim().replace('\\', '/');
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        // A leading slash means the top of the root, as in "/Oak_1", not the filesystem's root.
        while (path.startsWith("/")) path = path.substring(1);
        int slash = path.lastIndexOf('/');
        String folders = slash < 0 ? "" : path.substring(0, slash);
        String relative = folders.isEmpty()
            ? PrefabWriter.cleanName(path)
            : folders + "/" + PrefabWriter.cleanName(path);
        Path resolved = PathUtil.resolvePathWithinDir(root, relative);
        if (resolved == null) {
            throw new ScriptError(ScriptError.Phase.VALIDATE,
                "Prefab path \"" + raw + "\" escapes the prefabs directory.");
        }
        return resolved;
    }

    /** Whether a requested name carries a folder, and so names one place rather than asking for a search. */
    public static boolean hasFolder(String raw) {
        String path = raw.trim().replace('\\', '/');
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path.indexOf('/') >= 0;
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

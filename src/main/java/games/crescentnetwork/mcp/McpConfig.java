package games.crescentnetwork.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The plugin's on-disk settings, written to {@code mcp.json} in its data directory.
 *
 * <p>Created on first load with the defaults filled in, so there is a file to edit without having to
 * know the format or that the option exists. Once written it is the source of truth and is never
 * rewritten, so an edit survives a restart and a later version adding a field does not clobber it.
 *
 * <p>Only the port for now. Unrecognised keys are preserved on load, so a field added later can be
 * merged in without losing anything an older or newer build wrote.
 */
public final class McpConfig {

    public static final String FILE_NAME = "mcp.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final int port;
    private final JsonObject raw;

    private McpConfig(int port, JsonObject raw) {
        this.port = port;
        this.raw = raw;
    }

    public int port() {
        return port;
    }

    /** The parsed file as-is, so a caller can read a key this class does not model yet. */
    public JsonObject raw() {
        return raw;
    }

    /**
     * Loads {@code mcp.json}, creating it with {@code defaultPort} when it is not there yet.
     *
     * <p>Never throws: a settings file that cannot be read or written must not stop the server
     * booting, so problems are reported through {@code onProblem} and the defaults are used.
     *
     * @param directory   the plugin's data directory
     * @param defaultPort the port to write when creating the file
     * @param onProblem   receives a human-readable description of anything that went wrong
     */
    public static McpConfig loadOrCreate(Path directory, int defaultPort, Problems onProblem) {
        Path file = directory.resolve(FILE_NAME);

        JsonObject parsed = null;
        if (Files.isRegularFile(file)) {
            try {
                parsed = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            } catch (Exception e) {
                // A malformed file is left alone rather than overwritten: it is probably a hand edit
                // with a typo, and silently replacing it would throw away whatever else was in there.
                onProblem.report("Could not read " + FILE_NAME + ", using defaults: " + e);
            }
        }

        if (parsed == null) {
            JsonObject created = new JsonObject();
            created.addProperty("port", defaultPort);
            write(file, created, onProblem);
            return new McpConfig(defaultPort, created);
        }

        int port = defaultPort;
        if (parsed.has("port")) {
            try {
                int configured = parsed.get("port").getAsInt();
                if (configured < 0 || configured > 65535) {
                    onProblem.report("Ignoring out-of-range \"port\" " + configured + " in " + FILE_NAME);
                } else {
                    port = configured;
                }
            } catch (RuntimeException e) {
                onProblem.report("Ignoring invalid \"port\" in " + FILE_NAME);
            }
        }
        return new McpConfig(port, parsed);
    }

    private static void write(Path file, JsonObject contents, Problems onProblem) {
        try {
            Files.createDirectories(file.getParent());
            // Written via a temporary file so a crash mid-write cannot leave a half-written settings
            // file that fails to parse on the next boot.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(contents) + System.lineSeparator());
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            onProblem.report("Could not write " + FILE_NAME + ": " + e);
        }
    }

    /** Where a load or save problem gets reported, so this class does not depend on a logger. */
    @FunctionalInterface
    public interface Problems {
        void report(String message);
    }

    @Nullable
    public static Path fileIn(@Nullable Path directory) {
        return directory == null ? null : directory.resolve(FILE_NAME);
    }
}

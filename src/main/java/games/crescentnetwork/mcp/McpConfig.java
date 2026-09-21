package games.crescentnetwork.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.crescentnetwork.mcp.auth.McpTokens;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The plugin's on-disk settings, written to {@code mcp.json} in its data directory.
 *
 * <p>Created on first load with the defaults filled in, so there is a file to edit without having to
 * know the format or that the option exists. After that it is the source of truth; it is rewritten
 * only to add a setting that is missing, and keys this version does not recognise are carried
 * through untouched.
 */
public final class McpConfig {

    public static final String FILE_NAME = "mcp.json";

    public static final String PORT = "port";
    public static final String TOKEN_SECRET = "tokenSecret";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final int port;
    private final String tokenSecret;
    private final JsonObject raw;

    private McpConfig(int port, String tokenSecret, JsonObject raw) {
        this.port = port;
        this.tokenSecret = tokenSecret;
        this.raw = raw;
    }

    public int port() {
        return port;
    }

    /**
     * The salt every personal token is derived from.
     *
     * <p>Generated once and kept. Replacing it invalidates every token that has been handed out,
     * which is how an operator revokes access to everyone at once.
     */
    public String tokenSecret() {
        return tokenSecret;
    }

    /** The parsed file as-is, so a caller can read a key this class does not model yet. */
    public JsonObject raw() {
        return raw;
    }

    /**
     * Loads {@code mcp.json}, creating it when absent and filling in any setting it is missing.
     *
     * <p>Never throws: a settings file that cannot be read or written must not stop the server
     * booting, so problems are reported through {@code onProblem} and the defaults are used.
     *
     * @param directory   the plugin's data directory
     * @param defaultPort the port to write when the file has none
     * @param onProblem   receives a human-readable description of anything that went wrong
     */
    public static McpConfig loadOrCreate(Path directory, int defaultPort, Problems onProblem) {
        Path file = directory.resolve(FILE_NAME);

        JsonObject contents = null;
        if (Files.isRegularFile(file)) {
            try {
                contents = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            } catch (Exception e) {
                // A malformed file is left alone rather than overwritten: it is probably a hand edit
                // with a typo, and replacing it would throw away whatever else was in there.
                onProblem.report("Could not read " + FILE_NAME + ", using defaults: " + e);
            }
        }

        boolean existed = contents != null;
        if (contents == null) contents = new JsonObject();
        boolean dirty = !existed;

        int port = defaultPort;
        if (contents.has(PORT)) {
            try {
                int configured = contents.get(PORT).getAsInt();
                if (configured < 0 || configured > 65535) {
                    onProblem.report("Ignoring out-of-range \"" + PORT + "\" " + configured + " in " + FILE_NAME);
                } else {
                    port = configured;
                }
            } catch (RuntimeException e) {
                onProblem.report("Ignoring invalid \"" + PORT + "\" in " + FILE_NAME);
            }
        } else {
            contents.addProperty(PORT, port);
            dirty = true;
        }

        String secret = null;
        if (contents.has(TOKEN_SECRET)) {
            try {
                String configured = contents.get(TOKEN_SECRET).getAsString();
                if (McpTokens.isUsableSecret(configured)) {
                    secret = configured;
                } else {
                    onProblem.report("\"" + TOKEN_SECRET + "\" in " + FILE_NAME
                        + " is too short to be a secret; generating a new one, which invalidates existing tokens");
                }
            } catch (RuntimeException e) {
                onProblem.report("Ignoring invalid \"" + TOKEN_SECRET + "\" in " + FILE_NAME);
            }
        }
        if (secret == null) {
            // Generated rather than defaulted: a shipped default would make every server's tokens
            // forgeable by anyone who read the source.
            secret = McpTokens.generateSecret();
            contents.addProperty(TOKEN_SECRET, secret);
            dirty = true;
        }

        if (dirty) write(file, contents, onProblem);
        return new McpConfig(port, secret, contents);
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

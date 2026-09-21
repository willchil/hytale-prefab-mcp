package games.crescentnetwork.mcp;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/** Reads model and texture assets for tests from a copy of the game's assets, when one is available. */
final class TestAssets {

    private TestAssets() {
    }

    /** Reads {@code Common/}-relative paths out of a directory holding the assets' {@code Common} folder. */
    static Function<String, byte[]> directory(Path common) {
        return path -> {
            try {
                Path file = common.resolve(strip(path)).normalize();
                if (!file.startsWith(common.normalize()) || !Files.isRegularFile(file)) return null;
                return Files.readAllBytes(file);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        };
    }

    /** The {@code Common} folder named by {@code MCP_ASSETS_DIR}, or null when it is unset. */
    @Nullable
    static Path fromEnvironment() {
        String dir = System.getenv("MCP_ASSETS_DIR");
        if (dir == null || dir.isBlank()) return null;
        Path path = Path.of(dir);
        return Files.isDirectory(path) ? path : null;
    }

    private static String strip(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        return p.startsWith("Common/") ? p.substring("Common/".length()) : p;
    }
}

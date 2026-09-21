package games.crescentnetwork.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers what a fresh config gets, what an existing one keeps, and how bad values are handled. */
class ConfigTest {

    private final List<String> problems = new ArrayList<>();

    private McpConfig load(Path dir) {
        return McpConfig.loadOrCreate(dir, 7520, problems::add);
    }

    private static JsonObject onDisk(Path dir) throws Exception {
        return JsonParser.parseString(Files.readString(dir.resolve(McpConfig.FILE_NAME))).getAsJsonObject();
    }

    @Test
    void aFreshServerGetsAFileWithTheDefaults(@TempDir Path dir) throws Exception {
        McpConfig config = load(dir);

        assertEquals(7520, config.port());
        assertFalse(config.requirePersonalToken(), "a new server should not require a token");
        assertTrue(problems.isEmpty(), () -> "unexpected problems: " + problems);

        JsonObject written = onDisk(dir);
        assertEquals(7520, written.get(McpConfig.PORT).getAsInt());
        assertFalse(written.get(McpConfig.REQUIRE_PERSONAL_TOKEN).getAsBoolean());
        assertTrue(written.has(McpConfig.TOKEN_SECRET));
    }

    @Test
    void theSaltIsRandomPerServer(@TempDir Path first, @TempDir Path second) {
        // Two fresh servers must not share a secret, or a token minted on one would work on the other.
        assertNotEquals(load(first).tokenSecret(), load(second).tokenSecret());
    }

    @Test
    void theSaltIsKeptAcrossRestarts(@TempDir Path dir) {
        // Regenerating it would silently invalidate every token that had been handed out.
        assertEquals(load(dir).tokenSecret(), load(dir).tokenSecret());
    }

    @Test
    void anExistingConfigGainsOnlyWhatItIsMissing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(McpConfig.FILE_NAME),
            "{\"port\": 9100, \"somethingAnotherVersionWrote\": \"keep me\"}");

        McpConfig config = load(dir);
        assertEquals(9100, config.port(), "an existing port must be respected");
        assertFalse(config.requirePersonalToken());

        JsonObject written = onDisk(dir);
        assertEquals(9100, written.get(McpConfig.PORT).getAsInt());
        assertTrue(written.has(McpConfig.TOKEN_SECRET), "a missing secret should be filled in");
        assertTrue(written.has(McpConfig.REQUIRE_PERSONAL_TOKEN), "a missing toggle should be filled in");
        assertEquals("keep me", written.get("somethingAnotherVersionWrote").getAsString(),
            "keys this version does not know about must survive");
    }

    @Test
    void anExplicitTrueIsHonoured(@TempDir Path dir) {
        assertTrue(writeThenLoad(dir, "{\"requirePersonalToken\": true}").requirePersonalToken());
    }

    @Test
    void anExplicitFalseIsHonoured(@TempDir Path dir) {
        assertFalse(writeThenLoad(dir, "{\"requirePersonalToken\": false}").requirePersonalToken());
    }

    @Test
    void aShortSecretIsReplacedAndReported(@TempDir Path dir) {
        McpConfig config = writeThenLoad(dir, "{\"tokenSecret\": \"tooshort\"}");
        assertNotEquals("tooshort", config.tokenSecret());
        assertTrue(problems.stream().anyMatch(p -> p.contains(McpConfig.TOKEN_SECRET)),
            () -> "expected a reported problem, got " + problems);
    }

    @Test
    void anOutOfRangePortFallsBackAndIsReported(@TempDir Path dir) {
        assertEquals(7520, writeThenLoad(dir, "{\"port\": 70000}").port());
        assertTrue(problems.stream().anyMatch(p -> p.contains(McpConfig.PORT)),
            () -> "expected a reported problem, got " + problems);
    }

    @Test
    void aMalformedFileFallsBackToDefaultsWithoutThrowing(@TempDir Path dir) {
        McpConfig config = writeThenLoad(dir, "{ this is not json");
        assertEquals(7520, config.port());
        assertFalse(config.requirePersonalToken());
        assertFalse(problems.isEmpty(), "a malformed file should be reported");
    }

    private McpConfig writeThenLoad(Path dir, String contents) {
        try {
            Files.writeString(dir.resolve(McpConfig.FILE_NAME), contents);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return load(dir);
    }
}

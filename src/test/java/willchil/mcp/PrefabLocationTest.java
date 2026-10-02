package willchil.mcp;

import willchil.mcp.mcp.McpCaller;
import willchil.mcp.prefab.PrefabLocation;
import willchil.mcp.script.ScriptError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers which directories a prefab lands in, that a username cannot escape them, and staging cleanup. */
class PrefabLocationTest {

    private static final UUID PLAYER = UUID.fromString("657827e9-8e16-4614-b533-4cd444373a46");

    @Test
    void anonymousCallersShareTheRootDirectory() {
        // The server is not requiring tokens, so there is nobody to file the prefab under.
        assertEquals("prefab-mcp", PrefabLocation.directoryFor(McpCaller.ANONYMOUS));
    }

    @Test
    void anOnlinePlayerGetsTheirOwnDirectory() {
        assertEquals("prefab-mcp/willchil",
            PrefabLocation.directoryFor(McpCaller.identified(PLAYER, "willchil")));
    }

    @Test
    void anOfflinePlayerGoesToTheSharedOfflineDirectory() {
        // A username only resolves while a player is connected, and tokens keep working when they
        // are not, so this is the ordinary case for an agent running overnight.
        assertEquals("prefab-mcp/offline-players",
            PrefabLocation.directoryFor(McpCaller.identified(PLAYER, null)));
    }

    @Test
    void differentPlayersNeverShareADirectory() {
        assertNotEquals(
            PrefabLocation.directoryFor(McpCaller.identified(PLAYER, "alice")),
            PrefabLocation.directoryFor(McpCaller.identified(UUID.randomUUID(), "bob")));
    }

    @Test
    void aUsernameCannotClimbOutOfTheDirectory() {
        for (String hostile : new String[]{"../../etc", "a/b", "a\\b", "..", ".", "../"}) {
            String directory = PrefabLocation.directoryFor(McpCaller.identified(PLAYER, hostile));
            assertFalse(directory.contains(".."), "must not contain ..: " + directory);
            assertEquals(2, directory.split("/").length,
                "must stay one level below the root: " + directory);
        }
    }

    @Test
    void aUsernameCannotImpersonateTheOfflineDirectory() {
        // Otherwise a player called "offline-players" would collect everyone else's offline builds.
        assertNotEquals("prefab-mcp/offline-players",
            PrefabLocation.directoryFor(McpCaller.identified(PLAYER, "offline-players")));
        assertNotEquals("prefab-mcp/offline-players",
            PrefabLocation.directoryFor(McpCaller.identified(PLAYER, "Offline-Players")));
    }

    @Test
    void anUnusableUsernameFallsBackRatherThanProducingAnEmptySegment() {
        assertEquals("prefab-mcp/offline-players",
            PrefabLocation.directoryFor(McpCaller.identified(PLAYER, "...")));
        assertEquals("prefab-mcp/offline-players",
            PrefabLocation.directoryFor(McpCaller.identified(PLAYER, "   ")));
    }

    @Test
    void ordinaryUsernamesSurviveIntact() {
        for (String name : new String[]{"willchil", "Player_1", "a-b.c", "ABC123"}) {
            assertEquals("prefab-mcp/" + name,
                PrefabLocation.directoryFor(McpCaller.identified(PLAYER, name)));
        }
    }

    @Test
    void theStagingSubfolderMatchesThePublishedOne() {
        assertNull(PrefabLocation.subfolderFor(McpCaller.ANONYMOUS));
        assertEquals("willchil", PrefabLocation.subfolderFor(McpCaller.identified(PLAYER, "willchil")));
        assertEquals(PrefabLocation.OFFLINE, PrefabLocation.subfolderFor(McpCaller.identified(PLAYER, null)));
    }

    @Test
    void stagingIsSplitPerCaller(@TempDir Path root) {
        assertEquals(root, PrefabLocation.stagingDirectory(root, McpCaller.ANONYMOUS));
        assertEquals(root.resolve("willchil"),
            PrefabLocation.stagingDirectory(root, McpCaller.identified(PLAYER, "willchil")));
        assertEquals(root.resolve(PrefabLocation.OFFLINE),
            PrefabLocation.stagingDirectory(root, McpCaller.identified(PLAYER, null)));
    }

    @Test
    void aUsernameCannotClimbOutOfStaging(@TempDir Path root) {
        for (String hostile : new String[]{"../../etc", "a/b", "a\\b", "..", ".", "../"}) {
            Path directory = PrefabLocation.stagingDirectory(root, McpCaller.identified(PLAYER, hostile));
            assertEquals(root, directory.getParent(), "must stay one level below the root: " + directory);
        }
    }

    @Test
    void clearingStagingRemovesEverything(@TempDir Path parent) throws Exception {
        Path root = parent.resolve("staging");
        Files.createDirectories(root.resolve("willchil"));
        Files.writeString(root.resolve("tower.prefab.json"), "{}");
        Files.writeString(root.resolve("willchil").resolve("house.prefab.json"), "{}");

        List<String> problems = new ArrayList<>();
        PrefabLocation.clearStaging(root, problems::add);

        assertFalse(Files.exists(root));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void clearingAMissingStagingAreaIsHarmless(@TempDir Path parent) {
        List<String> problems = new ArrayList<>();
        PrefabLocation.clearStaging(parent.resolve("never-created"), problems::add);
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void treeSearchFindsAPrefabInAnyFolderIgnoringCase(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("Trees/Oak"));
        Files.createDirectories(root.resolve("prefab-mcp/someone"));
        Files.writeString(root.resolve("Trees/Oak/Oak_1.prefab.json"), "{}");
        Files.writeString(root.resolve("prefab-mcp/someone/tower.lpf"), "");
        Files.writeString(root.resolve("Trees/Oak/Oak_1_notes.txt"), "");

        assertEquals(List.of(root.resolve("Trees/Oak/Oak_1")), PrefabLocation.findInTree(root, "oak_1"));
        assertEquals(List.of(root.resolve("prefab-mcp/someone/tower")), PrefabLocation.findInTree(root, "Tower"));
        assertTrue(PrefabLocation.findInTree(root, "Oak").isEmpty(), "a prefix must not match");
    }

    @Test
    void treeSearchListsEachMatchOnceAndInOrder(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("b"));
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("b/house.prefab.json"), "{}");
        // The same prefab saved in both formats is still one prefab.
        Files.writeString(root.resolve("a/house.prefab.json"), "{}");
        Files.writeString(root.resolve("a/house.lpf"), "");

        assertEquals(List.of(root.resolve("a/house"), root.resolve("b/house")),
            PrefabLocation.findInTree(root, "house"));
    }

    @Test
    void treeSearchOfAMissingDirectoryFindsNothing(@TempDir Path parent) {
        assertTrue(PrefabLocation.findInTree(parent.resolve("never-created"), "house").isEmpty());
    }

    @Test
    void aNestedNameResolvesInsideTheRoot(@TempDir Path root) {
        assertTrue(PrefabLocation.hasFolder("Trees/Oak_1"));
        assertTrue(PrefabLocation.hasFolder("Trees\\Oak_1.prefab.json"));
        assertFalse(PrefabLocation.hasFolder("Oak_1"));
        assertFalse(PrefabLocation.hasFolder("Oak_1/"));

        assertEquals(root.resolve("Trees/Oak_1"), PrefabLocation.resolveNested(root, "Trees/Oak_1"));
        assertEquals(root.resolve("Trees/Oak_1"), PrefabLocation.resolveNested(root, "Trees\\Oak_1.prefab.json"));
    }

    @Test
    void aNestedNameCannotClimbOutOfTheRoot(@TempDir Path root) {
        for (String hostile : new String[]{"../outside", "Trees/../../outside", "../../etc/passwd"}) {
            assertThrows(ScriptError.class, () -> PrefabLocation.resolveNested(root, hostile), hostile);
        }
    }
}

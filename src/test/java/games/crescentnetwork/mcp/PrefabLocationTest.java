package games.crescentnetwork.mcp;

import games.crescentnetwork.mcp.mcp.McpCaller;
import games.crescentnetwork.mcp.prefab.PrefabLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Covers which directory a prefab lands in, and that a username cannot escape it. */
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
}

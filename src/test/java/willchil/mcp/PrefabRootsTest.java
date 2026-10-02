package willchil.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import willchil.mcp.prefab.PrefabRoots;
import willchil.mcp.prefab.PrefabRoots.Found;
import willchil.mcp.prefab.PrefabRoots.Root;
import willchil.mcp.script.ScriptError;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the order prefab folders are searched in, and the names that reach each prefab. */
class PrefabRootsTest {

    /** Server prefabs, then the vanilla pack, then a plugin pack, as {@code current()} orders them. */
    private static List<Root> roots(Path base) throws Exception {
        Path server = base.resolve("prefabs");
        Path vanilla = base.resolve("vanilla/Server/Prefabs");
        Path plugin = base.resolve("plugin/Server/Prefabs");
        for (Path p : List.of(server.resolve("Trees"), vanilla.resolve("Trees"), plugin.resolve("Trees"))) {
            Files.createDirectories(p);
        }
        // Trees/Oak_1 is in all three; the server's copy hides both packs' copies.
        write(server.resolve("Trees/Oak_1.prefab.json"));
        write(vanilla.resolve("Trees/Oak_1.prefab.json"));
        write(plugin.resolve("Trees/Oak_1.lpf"));
        // Only in the vanilla pack, and hidden from the plugin's copy.
        write(vanilla.resolve("Trees/Birch_1.prefab.json"));
        write(plugin.resolve("Trees/Birch_1.prefab.json"));
        // Only in the plugin pack, at the top of its folder.
        write(plugin.resolve("Totem.prefab.json"));
        return List.of(new Root(null, server), new Root("Hytale:Hytale", vanilla), new Root("Acme:Trees", plugin));
    }

    private static void write(Path file) throws Exception {
        Files.writeString(file, "{}");
    }

    @Test
    void listingGoesServerThenVanillaThenPluginsAndQualifiesHiddenPrefabs(@TempDir Path base) throws Exception {
        List<Root> roots = roots(base);
        List<String> names = PrefabRoots.list(roots, null).stream().map(Found::name).toList();
        assertEquals(List.of(
            "Trees/Oak_1",
            "Trees/Birch_1",
            "@Hytale:Hytale/Trees/Oak_1",
            "/Totem",
            "@Acme:Trees/Trees/Birch_1",
            "@Acme:Trees/Trees/Oak_1"), names);
    }

    @Test
    void everyListedNameResolvesToTheSamePrefab(@TempDir Path base) throws Exception {
        List<Root> roots = roots(base);
        for (Found found : PrefabRoots.list(roots, null)) {
            assertTrue(PrefabRoots.isPath(found.name()), found.name());
            assertEquals(found.target(), PrefabRoots.resolve(roots, found.name()), found.name());
        }
    }

    @Test
    void aFolderScopeCoversThatFolderInEveryRoot(@TempDir Path base) throws Exception {
        List<String> names = PrefabRoots.list(roots(base), "Trees").stream().map(Found::name).toList();
        assertEquals(5, names.size(), names.toString());
        assertFalse(names.contains("/Totem"));
        assertTrue(PrefabRoots.list(roots(base), "Nowhere").isEmpty());
    }

    @Test
    void aPlainNameIsFoundInTheFirstRootThatHasIt(@TempDir Path base) throws Exception {
        List<Root> roots = roots(base);
        assertEquals(List.of(new Found("Trees/Oak_1", base.resolve("prefabs/Trees/Oak_1"))),
            PrefabRoots.findByName(roots, "oak_1"));
        assertEquals(List.of(new Found("Trees/Birch_1", base.resolve("vanilla/Server/Prefabs/Trees/Birch_1"))),
            PrefabRoots.findByName(roots, "Birch_1"));
        assertEquals(List.of(new Found("/Totem", base.resolve("plugin/Server/Prefabs/Totem"))),
            PrefabRoots.findByName(roots, "Totem"));
        assertTrue(PrefabRoots.findByName(roots, "Willow").isEmpty());
    }

    @Test
    void aPackQualifiedNameMustNameALoadedPackAndAPrefab(@TempDir Path base) throws Exception {
        List<Root> roots = roots(base);
        assertNull(PrefabRoots.resolve(roots, "@Hytale:Hytale/Trees/Willow"));
        assertThrows(ScriptError.class, () -> PrefabRoots.resolve(roots, "@Nobody:Nothing/Trees/Oak_1"));
        assertThrows(ScriptError.class, () -> PrefabRoots.resolve(roots, "@Hytale:Hytale"));
        assertThrows(ScriptError.class, () -> PrefabRoots.resolve(roots, "@Hytale:Hytale/../../prefabs/Trees/Oak_1"));
    }

    @Test
    void aZippedPackIsSearchedLikeAFolder(@TempDir Path base) throws Exception {
        Path zip = base.resolve("Assets.zip");
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
            Path prefabs = fs.getPath("/Server/Prefabs");
            Files.createDirectories(prefabs.resolve("Rocks"));
            write(prefabs.resolve("Rocks/Boulder_2.prefab.json"));

            List<Root> roots = List.of(new Root(null, base.resolve("prefabs")), new Root("Hytale:Hytale", prefabs));
            List<Found> listed = PrefabRoots.list(roots, null);
            assertEquals(List.of("Rocks/Boulder_2"), listed.stream().map(Found::name).toList());
            assertEquals(listed.get(0).target(), PrefabRoots.resolve(roots, "Rocks/Boulder_2"));
            assertEquals(listed, PrefabRoots.findByName(roots, "boulder_2"));
        }
    }
}

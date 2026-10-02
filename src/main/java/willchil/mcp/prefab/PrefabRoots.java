package willchil.mcp.prefab;

import com.hypixel.hytale.common.util.PathUtil;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import willchil.mcp.script.ScriptError;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The directories outside staging that hold prefabs a caller can read, in the order they are searched:
 * the server's {@code prefabs/} directory, then the vanilla asset pack's {@code Server/Prefabs}, then
 * every other loaded asset pack's, in the order the server loaded them.
 *
 * <p>A prefab is named by its path inside its root, such as {@code Trees/Oak_1}. When an earlier root
 * has a prefab at the same path, the later one is shadowed, and is named {@code @<pack>/Trees/Oak_1}
 * instead so it can still be reached. A prefab directly inside a root is named with a leading slash,
 * such as {@code /Oak_1}, so it reads as a path and not as a name to search for.
 */
public final class PrefabRoots {

    /** Marks a name qualified by the asset pack it comes from. */
    public static final String PACK_PREFIX = "@";

    private static final String[] PREFAB_SUFFIXES = {".prefab.json", ".lpf"};

    /** @param pack the asset pack's name, or null for the server's own {@code prefabs/} directory */
    public record Root(@Nullable String pack, Path directory) {
    }

    /** A prefab found in one of the roots, with the name that reaches it. */
    public record Found(String name, Path target) {
    }

    private PrefabRoots() {
    }

    /** The roots of the running server, in search order. Packs without a prefabs folder are left out. */
    public static List<Root> current() {
        PrefabStore store = PrefabStore.get();
        List<Root> roots = new ArrayList<>();
        roots.add(new Root(null, store.getServerPrefabsPath()));
        List<Root> thirdParty = new ArrayList<>();
        for (PrefabStore.AssetPackPrefabPath path : store.getAllAssetPrefabPaths()) {
            Root root = new Root(path.pack().getName(), path.prefabsPath());
            if (path.isBasePack()) roots.add(root);
            else thirdParty.add(root);
        }
        roots.addAll(thirdParty);
        return roots;
    }

    /**
     * Resolves a name that is a path: {@code @<pack>/folder/name} inside that pack, otherwise
     * {@code folder/name} in the first root that has it.
     *
     * @return the prefab's path without its suffix, or null when no root has it
     * @throws ScriptError when the path would leave its root, or names a pack that is not loaded
     */
    @Nullable
    public static Path resolve(List<Root> roots, String raw) {
        String path = raw.trim().replace('\\', '/');
        if (path.startsWith(PACK_PREFIX)) {
            int slash = path.indexOf('/');
            String pack = slash < 0 ? path.substring(PACK_PREFIX.length()) : path.substring(PACK_PREFIX.length(), slash);
            if (slash < 0 || slash == path.length() - 1) {
                throw new ScriptError(ScriptError.Phase.VALIDATE,
                    "\"" + raw.trim() + "\" names a pack but no prefab. Use @" + pack + "/<path>.");
            }
            for (Root root : roots) {
                if (root.pack() != null && root.pack().equalsIgnoreCase(pack)) {
                    Path target = PrefabLocation.resolveNested(root.directory(), path.substring(slash));
                    return exists(target) ? target : null;
                }
            }
            throw new ScriptError(ScriptError.Phase.VALIDATE,
                "No loaded asset pack named \"" + pack + "\" has prefabs.");
        }
        for (Root root : roots) {
            Path target = PrefabLocation.resolveNested(root.directory(), path);
            if (exists(target)) return target;
        }
        return null;
    }

    /**
     * Every prefab whose file name is {@code cleanedName}, from the first root that has any, so a
     * name in the server's own {@code prefabs/} wins over the same name in an asset pack.
     */
    public static List<Found> findByName(List<Root> roots, String cleanedName) {
        for (int i = 0; i < roots.size(); i++) {
            List<Path> matches = PrefabLocation.findInTree(roots.get(i).directory(), cleanedName);
            if (matches.isEmpty()) continue;
            List<Found> found = new ArrayList<>(matches.size());
            for (Path target : matches) found.add(new Found(nameOf(roots, i, target), target));
            return found;
        }
        return List.of();
    }

    /**
     * Every prefab in {@code roots}, in search order and sorted by path within each root.
     *
     * @param folder only prefabs inside this folder of each root, or null for everything
     */
    public static List<Found> list(List<Root> roots, @Nullable String folder) {
        List<Found> found = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            Path directory = roots.get(i).directory();
            if (folder != null) {
                directory = PathUtil.resolvePathWithinDir(directory, folder);
                if (directory == null) continue;
            }
            for (Path target : PrefabLocation.listInTree(directory, true)) {
                found.add(new Found(nameOf(roots, i, target), target));
            }
        }
        return found;
    }

    /** The name that reaches {@code target} in {@code roots.get(index)}, qualified when shadowed. */
    static String nameOf(List<Root> roots, int index, Path target) {
        Root root = roots.get(index);
        String relative = root.directory().relativize(target).toString().replace('\\', '/');
        for (int i = 0; i < index; i++) {
            Path earlier = PathUtil.resolvePathWithinDir(roots.get(i).directory(), relative);
            if (earlier != null && exists(earlier)) {
                return PACK_PREFIX + root.pack() + "/" + relative;
            }
        }
        return relative.indexOf('/') < 0 ? "/" + relative : relative;
    }

    /** Whether a prefab is saved at {@code target} in either format; works inside a zipped pack too. */
    static boolean exists(Path target) {
        String fileName = target.getFileName() == null ? "" : target.getFileName().toString();
        for (String suffix : PREFAB_SUFFIXES) {
            if (Files.isRegularFile(target.resolveSibling(fileName + suffix))) return true;
        }
        return false;
    }

    /** Whether {@code raw} names a path rather than a prefab to search for by name. */
    public static boolean isPath(String raw) {
        String path = raw.trim();
        return path.startsWith(PACK_PREFIX) || PrefabLocation.hasFolder(path);
    }
}

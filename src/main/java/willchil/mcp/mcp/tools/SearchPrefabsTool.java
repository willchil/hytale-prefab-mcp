package willchil.mcp.mcp.tools;

import com.google.gson.JsonObject;
import com.hypixel.hytale.common.util.PathUtil;
import willchil.mcp.http.JsonRpc;
import willchil.mcp.mcp.McpCaller;
import willchil.mcp.mcp.McpServices;
import willchil.mcp.mcp.McpTool;
import willchil.mcp.prefab.PrefabLoader;
import willchil.mcp.prefab.PrefabLocation;
import willchil.mcp.prefab.PrefabRoots;
import willchil.mcp.script.BuildRecorder;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lists the prefabs a caller can render or paste, so names do not have to be guessed. */
public final class SearchPrefabsTool implements McpTool {

    private static final int DEFAULT_LIMIT = 40;
    private static final int MAX_LIMIT = 200;

    private final McpServices services;

    public SearchPrefabsTool(McpServices services) {
        this.services = services;
    }

    @Override
    public String name() {
        return "search_prefabs";
    }

    @Override
    public String description() {
        return """
            Search the prefabs you can render with render_prefab or paste with prefab() in a \
            build_prefab script. Returns each prefab's name, its size and how many blocks it holds.

            Prefabs come from four places, searched and listed in this order: your own staged \
            builds, the server's prefabs/ directory (including prefabs from other players, the in-game \
            editor and operators), the vanilla Hytale asset pack, then asset packs added by plugins.

            The name column is exactly what render_prefab and prefab() accept: a plain name for one \
            of your own staged builds, otherwise the prefab's path inside its folder, such as \
            "Trees/Oak_1", or "/Oak_1" for one at the top. When an earlier place has a prefab at the \
            same path, the later one is named "@<pack>/Trees/Oak_1" so it can still be reached. \
            Within each place results are sorted by path, so prefabs in the same folder sit together. \
            The order is stable, so when a search hits its limit, pass offset to page through the rest.

            scope "all" (the default) searches everywhere. "mine" searches only your staged builds \
            and the prefabs you have published. Any other value is a folder, such as "Trees", \
            searched with everything beneath it in prefabs/ and in every asset pack. Leave query out \
            to list everything in scope.""";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = Schema.object();
        Schema.prop(schema, "query", "string",
            "Text matched against the prefab's path, case-insensitive. Multiple words must all appear, "
                + "e.g. \"oak tree\". Omit to list everything in scope.");
        JsonObject scope = Schema.prop(schema, "scope", "string",
            "\"all\", \"mine\", or a folder such as \"Trees\".");
        Schema.defaultValue(scope, "all");
        JsonObject limit = Schema.prop(schema, "limit", "integer",
            "Maximum results (default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ").");
        Schema.defaultValue(limit, DEFAULT_LIMIT);
        JsonObject offset = Schema.prop(schema, "offset", "integer",
            "How many matches to skip, for the next page of a search that hit its limit.");
        Schema.defaultValue(offset, 0);
        return schema;
    }

    /** One prefab in scope: the name to show and pass back, and where to read it from. */
    private record Entry(String name, Path target, @Nullable Path stagingDirectory) {
    }

    @Override
    public ToolResult call(JsonObject arguments, McpCaller caller) {
        String query = JsonRpc.stringOr(arguments, "query", null);
        String scope = JsonRpc.stringOr(arguments, "scope", "all");
        scope = scope == null || scope.isBlank() ? "all" : scope.trim();
        int limit = Math.max(1, Math.min(JsonRpc.intOr(arguments, "limit", DEFAULT_LIMIT), MAX_LIMIT));
        int offset = Math.max(0, JsonRpc.intOr(arguments, "offset", 0));

        List<PrefabRoots.Root> roots = PrefabRoots.current();
        Path prefabsRoot = roots.get(0).directory();
        Path staging = PrefabLocation.stagingDirectory(services.stagingRoot(), caller);

        List<Entry> inScope = new ArrayList<>();
        switch (scope.toLowerCase(Locale.ROOT)) {
            case "all" -> {
                addStaged(inScope, staging);
                addRoots(inScope, PrefabRoots.list(roots, null));
            }
            case "mine" -> {
                addStaged(inScope, staging);
                // Not recursive: an anonymous caller publishes to prefab-mcp/ itself, and every
                // player's own folder sits inside it.
                addTree(inScope, prefabsRoot, PrefabLocation.publishedDirectory(caller), false);
            }
            default -> {
                String folder = scope.replace('\\', '/');
                while (folder.endsWith("/")) folder = folder.substring(0, folder.length() - 1);
                if (PathUtil.resolvePathWithinDir(prefabsRoot, folder) == null) {
                    return ToolResult.failure("scope \"" + scope + "\" escapes the prefabs directory.");
                }
                addRoots(inScope, PrefabRoots.list(roots, folder));
                if (inScope.isEmpty()) {
                    return ToolResult.failure("No prefabs in a folder \"" + folder + "\" in prefabs/ or any "
                        + "asset pack. Use \"all\", \"mine\", or a folder from a name listed by an earlier search.");
                }
            }
        }

        List<Entry> hits = new ArrayList<>();
        for (Entry entry : inScope) {
            if (matches(entry.name(), query)) hits.add(entry);
        }
        if (hits.isEmpty()) {
            return ToolResult.text(inScope.isEmpty()
                ? "No prefabs in scope \"" + scope + "\"."
                : "No prefabs matched. " + inScope.size() + " prefabs are in scope \"" + scope
                    + "\"; try a broader query.");
        }

        StringBuilder out = new StringBuilder();
        out.append("Matched ").append(hits.size()).append(" of ").append(inScope.size())
            .append(" prefabs in scope \"").append(scope).append('"');
        if (offset >= hits.size()) {
            out.append(". offset ").append(offset).append(" is past the last match; the last page starts at offset ")
                .append((hits.size() - 1) / limit * limit).append(".\n");
            return ToolResult.text(out.toString());
        }
        int end = Math.min(offset + limit, hits.size());
        if (offset > 0 || end < hits.size()) {
            out.append(", showing ").append(offset + 1).append('-').append(end);
        }
        out.append(".\n\nname  |  size (w x h x l)  |  blocks\n");
        for (Entry entry : hits.subList(offset, end)) {
            out.append(entry.name()).append("  |  ");
            try {
                BuildRecorder recorder = read(entry);
                BuildRecorder.Bounds b = recorder.bounds();
                out.append(b.width()).append(" x ").append(b.height()).append(" x ").append(b.length())
                    .append("  |  ").append(recorder.blockCount());
            } catch (RuntimeException e) {
                out.append("unreadable  |  -");
            }
            out.append('\n');
        }
        if (end < hits.size()) {
            out.append("... and ").append(hits.size() - end).append(" more. Pass offset=").append(end)
                .append(" for the next page, or narrow the query or scope.\n");
        }
        return ToolResult.text(out.toString());
    }

    /**
     * A staged build of this session is already in memory; anything else is read from disk.
     *
     * @throws RuntimeException when the prefab cannot be read, {@code ScriptError} included
     */
    private BuildRecorder read(Entry entry) {
        if (entry.stagingDirectory() != null) {
            BuildRecorder cached = services.recentBuild(entry.stagingDirectory().toString(), entry.name());
            if (cached != null) return cached;
        }
        return PrefabLoader.loadTarget(entry.name(), entry.target());
    }

    private static void addStaged(List<Entry> into, Path staging) {
        // Not recursive: an anonymous caller's staging is the root that every player's folder sits in.
        for (Path target : PrefabLocation.listInTree(staging, false)) {
            into.add(new Entry(target.getFileName().toString(), target, staging));
        }
    }

    private static void addRoots(List<Entry> into, List<PrefabRoots.Found> found) {
        for (PrefabRoots.Found prefab : found) into.add(new Entry(prefab.name(), prefab.target(), null));
    }

    private static void addTree(List<Entry> into, Path prefabsRoot, Path directory, boolean recursive) {
        for (Path target : PrefabLocation.listInTree(directory, recursive)) {
            into.add(new Entry(prefabsRoot.relativize(target).toString().replace('\\', '/'), target, null));
        }
    }

    /** Whether every whitespace-separated word of {@code query} appears in {@code name}, ignoring case. */
    public static boolean matches(String name, @Nullable String query) {
        if (query == null || query.isBlank()) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String word : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!lower.contains(word)) return false;
        }
        return true;
    }
}

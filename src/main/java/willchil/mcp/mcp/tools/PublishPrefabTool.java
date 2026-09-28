package willchil.mcp.mcp.tools;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import willchil.mcp.http.JsonRpc;
import willchil.mcp.mcp.McpCaller;
import willchil.mcp.mcp.McpServices;
import willchil.mcp.mcp.McpTool;
import willchil.mcp.prefab.PrefabLocation;
import willchil.mcp.prefab.PrefabWriter;
import willchil.mcp.script.ScriptError;

import java.nio.file.Path;

/** Copies a staged build into the server's prefabs directory, where players can see it. */
public final class PublishPrefabTool implements McpTool {

    private final McpServices services;

    public PublishPrefabTool(McpServices services) {
        this.services = services;
    }

    @Override
    public String name() {
        return "publish_prefab";
    }

    @Override
    public String description() {
        return """
            Publish a prefab you built with build_prefab, copying it from your private staging area \
            into the server's prefabs/prefab-mcp/ folder (your own subfolder when the server knows who \
            you are), where players can find and paste it.

            Publish only once you have rendered the build and it looks right. The staged copy is kept, \
            so you can keep iterating and publish again with overwrite=true.""";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = Schema.object();
        Schema.prop(schema, "name", "string",
            "Name of the staged prefab, as passed to build_prefab.");
        JsonObject overwrite = Schema.prop(schema, "overwrite", "boolean",
            "Replace an existing published prefab of the same name.");
        Schema.defaultValue(overwrite, false);
        Schema.required(schema, "name");
        return schema;
    }

    @Override
    public ToolResult call(JsonObject arguments, McpCaller caller) {
        String name = JsonRpc.stringOr(arguments, "name", "").trim();
        if (name.isEmpty()) return ToolResult.failure("A prefab name is required.");
        boolean overwrite = JsonRpc.booleanOr(arguments, "overwrite", false);

        Path written;
        try {
            written = PrefabWriter.publish(name,
                PrefabLocation.stagingDirectory(services.stagingRoot(), caller),
                PrefabLocation.publishedDirectory(caller),
                overwrite);
        } catch (ScriptError e) {
            return ToolResult.failure(BuildPrefabTool.format(e));
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.failure("Publish failed unexpectedly: " + message);
        }

        return ToolResult.text("Published " + PrefabWriter.cleanName(name) + " to prefabs/"
            + PrefabStore.get().getServerPrefabsPath().relativize(written).toString().replace('\\', '/'));
    }
}

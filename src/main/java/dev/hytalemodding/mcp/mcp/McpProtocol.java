package dev.hytalemodding.mcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hytalemodding.mcp.http.JsonRpc;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches MCP methods over JSON-RPC.
 *
 * <p>Only the tools half of MCP is implemented, because that is all this server offers: no resources,
 * no prompts, and no server-initiated notifications, so the plain request/response shape of Streamable
 * HTTP is enough and no SSE stream is needed.
 */
public final class McpProtocol {

    public static final String SERVER_NAME = "hytale-prefab";
    public static final String SERVER_VERSION = "1.0.0";

    /** Protocol revisions this server understands; the newest is the default. */
    private static final List<String> SUPPORTED_PROTOCOLS =
        List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpProtocol(List<McpTool> tools) {
        for (McpTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
    }

    /**
     * Handles one request.
     *
     * @return the response, or null when the request was a notification and needs no reply
     */
    @Nullable
    public JsonObject handle(JsonObject request) {
        JsonElement id = JsonRpc.idOf(request);
        boolean notification = JsonRpc.isNotification(request);

        String method = JsonRpc.stringOr(request, "method", "");
        if (method.isEmpty()) {
            return notification ? null : JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "Missing method");
        }
        JsonObject params = JsonRpc.objectOrNull(request, "params");

        try {
            JsonObject result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> new JsonObject();
                case "tools/list" -> listTools();
                case "tools/call" -> callTool(params);
                default -> null;
            };
            if (result == null) {
                if (method.startsWith("notifications/")) {
                    // initialized, cancelled and friends need no reply and no bookkeeping here.
                    return null;
                }
                return notification ? null : JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND,
                    "Unknown method: " + method);
            }
            return notification ? null : JsonRpc.result(id, result);
        } catch (InvalidParams e) {
            return notification ? null : JsonRpc.error(id, JsonRpc.INVALID_PARAMS, e.getMessage());
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return notification ? null : JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, message);
        }
    }

    private JsonObject initialize(@Nullable JsonObject params) {
        String requested = JsonRpc.stringOr(params, "protocolVersion", SUPPORTED_PROTOCOLS.get(0));
        String negotiated = SUPPORTED_PROTOCOLS.contains(requested) ? requested : SUPPORTED_PROTOCOLS.get(0);

        JsonObject capabilities = new JsonObject();
        // An empty tools object advertises the capability without listChanged notifications, which
        // this server does not send: the palette is snapshotted, not streamed.
        capabilities.add("tools", new JsonObject());

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", SERVER_VERSION);

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", negotiated);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions",
            "Author Hytale prefabs. Start with search_blocks to find real block names on this server, "
                + "check a material with get_block_texture, build with build_prefab, then look at the "
                + "result with render_prefab and iterate.");
        return result;
    }

    private JsonObject listTools() {
        JsonArray array = new JsonArray();
        for (McpTool tool : tools.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", tool.name());
            entry.addProperty("description", tool.description());
            entry.add("inputSchema", tool.inputSchema());
            array.add(entry);
        }
        JsonObject result = new JsonObject();
        result.add("tools", array);
        return result;
    }

    private JsonObject callTool(@Nullable JsonObject params) {
        String name = JsonRpc.stringOr(params, "name", "");
        if (name.isEmpty()) throw new InvalidParams("tools/call requires a tool name");

        McpTool tool = tools.get(name);
        if (tool == null) throw new InvalidParams("Unknown tool: " + name);

        JsonObject arguments = JsonRpc.objectOrNull(params, "arguments");
        if (arguments == null) arguments = new JsonObject();

        try {
            return tool.call(arguments).toJson();
        } catch (RuntimeException e) {
            // A tool blowing up is reported through the result rather than as a JSON-RPC error, so the
            // model sees it as a failed attempt it can retry instead of as a broken connection.
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return McpTool.ToolResult.failure(name + " failed: " + message).toJson();
        }
    }

    public int toolCount() {
        return tools.size();
    }

    static final class InvalidParams extends RuntimeException {
        InvalidParams(String message) {
            super(message);
        }
    }
}

package games.crescentnetwork.mcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Base64;

/** One MCP tool: a name, a schema the client validates against, and a call. */
public interface McpTool {

    String name();

    String description();

    /** JSON Schema for the tool's arguments, as advertised by {@code tools/list}. */
    JsonObject inputSchema();

    /** @param caller who the call is running for; anonymous unless tokens are required */
    ToolResult call(JsonObject arguments, McpCaller caller);

    /**
     * What a tool hands back.
     *
     * <p>{@code isError} is the MCP-level signal that the call failed in a way the model should read
     * and react to, as distinct from a JSON-RPC error, which means the request itself was malformed.
     * A bad block name is the former: the request was fine, the build was not.
     */
    record ToolResult(JsonArray content, boolean isError) {

        public static ToolResult text(String text) {
            return new ToolResult(singleText(text, "text"), false);
        }

        public static ToolResult failure(String text) {
            return new ToolResult(singleText(text, "text"), true);
        }

        public static ToolResult image(byte[] png, String mimeType, String caption) {
            JsonArray content = new JsonArray();
            if (caption != null && !caption.isEmpty()) {
                JsonObject textPart = new JsonObject();
                textPart.addProperty("type", "text");
                textPart.addProperty("text", caption);
                content.add(textPart);
            }
            JsonObject imagePart = new JsonObject();
            imagePart.addProperty("type", "image");
            imagePart.addProperty("data", Base64.getEncoder().encodeToString(png));
            imagePart.addProperty("mimeType", mimeType);
            content.add(imagePart);
            return new ToolResult(content, false);
        }

        private static JsonArray singleText(String text, String type) {
            JsonObject part = new JsonObject();
            part.addProperty("type", type);
            part.addProperty("text", text);
            JsonArray content = new JsonArray();
            content.add(part);
            return content;
        }

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.add("content", content);
            if (isError) out.addProperty("isError", true);
            return out;
        }
    }

    /** Small helpers for building the JSON Schema fragments every tool needs. */
    final class Schema {

        private Schema() {
        }

        public static JsonObject object() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", new JsonObject());
            return schema;
        }

        public static JsonObject prop(JsonObject schema, String name, String type, String description) {
            JsonObject field = new JsonObject();
            field.addProperty("type", type);
            field.addProperty("description", description);
            schema.getAsJsonObject("properties").add(name, field);
            return field;
        }

        public static void enumValues(JsonObject field, String... values) {
            JsonArray array = new JsonArray();
            for (String v : values) array.add(v);
            field.add("enum", array);
        }

        public static void required(JsonObject schema, String... names) {
            JsonArray array = new JsonArray();
            for (String n : names) array.add(n);
            schema.add("required", array);
        }

        public static void defaultValue(JsonObject field, Number value) {
            field.addProperty("default", value);
        }

        public static void defaultValue(JsonObject field, String value) {
            field.addProperty("default", value);
        }

        public static void defaultValue(JsonObject field, boolean value) {
            field.addProperty("default", value);
        }
    }
}

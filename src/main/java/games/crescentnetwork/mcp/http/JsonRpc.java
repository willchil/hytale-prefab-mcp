package games.crescentnetwork.mcp.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;

/**
 * JSON-RPC 2.0 envelopes.
 *
 * <p>Gson comes off the server classpath, so no JSON library is bundled: the plugin classloader is
 * parent-first and the server's copy would win over a shaded one anyway.
 */
public final class JsonRpc {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private JsonRpc() {
    }

    public static JsonObject result(@Nullable JsonElement id, JsonElement payload) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("result", payload);
        return response;
    }

    public static JsonObject error(@Nullable JsonElement id, int code, String message) {
        return error(id, code, message, null);
    }

    public static JsonObject error(@Nullable JsonElement id, int code, String message, @Nullable JsonElement data) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        if (data != null) error.add("data", data);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("error", error);
        return response;
    }

    /** A request without an id is a notification, and a notification gets no reply. */
    public static boolean isNotification(JsonObject request) {
        return !request.has("id") || request.get("id").isJsonNull();
    }

    @Nullable
    public static JsonElement idOf(JsonObject request) {
        if (!request.has("id") || request.get("id").isJsonNull()) return null;
        return request.get("id");
    }

    public static String stringOr(@Nullable JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static int intOr(@Nullable JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static double doubleOr(@Nullable JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean booleanOr(@Nullable JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Nullable
    public static JsonObject objectOrNull(@Nullable JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) return null;
        return object.getAsJsonObject(key);
    }
}

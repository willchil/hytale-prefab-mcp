package games.crescentnetwork.mcp.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import games.crescentnetwork.mcp.auth.McpAuthenticator;
import games.crescentnetwork.mcp.mcp.McpCaller;
import games.crescentnetwork.mcp.mcp.McpProtocol;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Hosts the MCP endpoint over the JDK's own HTTP server.
 *
 * <p>No HTTP library is bundled. {@code com.sun.net.httpserver} is part of the JDK, Netty on the
 * server classpath is shared with the game's own network stack and best left alone, and there is
 * already a plugin in this tree serving a status endpoint exactly this way.
 *
 * <p>By default the listener binds to loopback only, so it is reachable by an agent on this machine
 * and by nothing else. With {@code localOnly} off it binds to {@code 0.0.0.0} instead and accepts
 * connections from any machine that can reach the port. Either way, requests carrying a cross-origin
 * {@code Origin} header are refused, which is what stops a web page in a user's browser from driving
 * the server through DNS rebinding.
 */
public final class McpHttpServer {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final String ENDPOINT = "/mcp";

    /** Scripts can be long, but nothing legitimate here approaches this. */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private static final int WORKER_THREADS = 2;

    /** Written as a literal so binding never waits on a name lookup. */
    private static final String ALL_INTERFACES = "0.0.0.0";

    private final McpProtocol protocol;
    private final McpAuthenticator authenticator;

    @Nullable
    private HttpServer server;
    @Nullable
    private ExecutorService executor;
    private int boundPort = -1;
    private boolean localOnly = true;

    public McpHttpServer(McpProtocol protocol, McpAuthenticator authenticator) {
        this.protocol = protocol;
        this.authenticator = authenticator;
    }

    /**
     * Starts the listener.
     *
     * <p>Never throws: a monitoring or tooling endpoint must not be able to stop the game server from
     * booting. A failure is logged and the plugin carries on doing nothing.
     *
     * @param localOnly true to bind to loopback only, false to bind to {@code 0.0.0.0}
     * @return true if the listener is up
     */
    public boolean start(int port, boolean localOnly) {
        try {
            InetSocketAddress address = localOnly
                ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port)
                : new InetSocketAddress(ALL_INTERFACES, port);
            HttpServer created = HttpServer.create(address, 0);

            AtomicInteger counter = new AtomicInteger();
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "hytale-mcp-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
            ExecutorService pool = Executors.newFixedThreadPool(WORKER_THREADS, factory);

            created.setExecutor(pool);
            created.createContext(ENDPOINT, this::handle);
            created.createContext("/health", this::handleHealth);
            created.start();

            this.server = created;
            this.executor = pool;
            this.boundPort = created.getAddress().getPort();
            this.localOnly = localOnly;
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).log("MCP server failed to start on port %d: %s", port, e.toString());
            return false;
        }
    }

    public void stop() {
        HttpServer current = server;
        if (current != null) {
            try {
                current.stop(0);
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).log("MCP server failed to stop cleanly: %s", e.toString());
            }
            server = null;
        }
        ExecutorService pool = executor;
        if (pool != null) {
            pool.shutdownNow();
            executor = null;
        }
        boundPort = -1;
    }

    public int boundPort() {
        return boundPort;
    }

    /** Whether the listener is up, so callers can report the address rather than guess at it. */
    public boolean isRunning() {
        return server != null && boundPort > 0;
    }

    /** Whether the running listener is bound to loopback, and so reachable from this machine only. */
    public boolean isLocalOnly() {
        return localOnly;
    }

    public String url() {
        return urlFor("127.0.0.1");
    }

    /**
     * The endpoint URL as reached through {@code host}.
     *
     * <p>The port is known, the host is not: the address a client should actually use depends on
     * where that client runs relative to this server, and on any proxy or DNS name in front. Callers
     * pass either a concrete host or a placeholder for the operator to fill in.
     */
    public String urlFor(String host) {
        return "http://" + host + ":" + boundPort + ENDPOINT;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        byte[] body = ("{\"status\":\"ok\",\"server\":\"" + McpProtocol.SERVER_NAME
            + "\",\"tools\":" + protocol.toolCount() + "}").getBytes(StandardCharsets.UTF_8);
        respond(exchange, 200, "application/json", body);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!isOriginAllowed(exchange.getRequestHeaders().getFirst("Origin"))) {
                respondText(exchange, 403, "Cross-origin requests are not accepted.");
                return;
            }
            if ("GET".equals(method)) {
                // The spec allows a GET to open an SSE stream. Nothing here pushes events, so the
                // correct answer is that the method is not allowed on this endpoint.
                exchange.getResponseHeaders().add("Allow", "POST");
                respondText(exchange, 405, "This MCP endpoint is POST only; it does not stream events.");
                return;
            }
            if (!"POST".equals(method)) {
                exchange.getResponseHeaders().add("Allow", "POST");
                respondText(exchange, 405, "Method not allowed.");
                return;
            }

            // Checked before the body is read: an unauthenticated caller should not be able to make
            // the server buffer megabytes of script for it.
            McpAuthenticator.Outcome auth =
                authenticator.authenticate(exchange.getRequestHeaders().getFirst("Authorization"));
            if (!auth.allowed()) {
                refuse(exchange, auth.failure());
                return;
            }

            byte[] raw = readBody(exchange);
            if (raw == null) {
                respondJson(exchange, 413,
                    JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Request body exceeds " + MAX_BODY_BYTES + " bytes"));
                return;
            }

            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                respondJson(exchange, 400, JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Invalid JSON"));
                return;
            }

            McpCaller caller = auth.player() == null
                ? McpCaller.ANONYMOUS
                : McpCaller.identified(auth.player(), auth.playerName());
            JsonElement response = dispatch(parsed, caller);
            if (response == null) {
                // Every message in the payload was a notification, so there is nothing to send back.
                respond(exchange, 202, "application/json", new byte[0]);
                return;
            }
            respondJson(exchange, 200, response);
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).log("MCP request failed: %s", e.toString());
            try {
                respondJson(exchange, 500, JsonRpc.error(null, JsonRpc.INTERNAL_ERROR, "Internal error"));
            } catch (IOException ignored) {
                // The client is gone; nothing useful left to do.
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * Refuses a request, distinguishing "who are you" from "you may not".
     *
     * <p>401 carries a {@code WWW-Authenticate} challenge so a client knows what kind of credential
     * is wanted; 403 means the credential was understood and the answer is still no.
     */
    private void refuse(HttpExchange exchange, McpAuthenticator.Failure failure) throws IOException {
        int status = failure == McpAuthenticator.Failure.FORBIDDEN ? 403 : 401;
        if (status == 401) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"" + McpProtocol.SERVER_NAME + "\"");
        }
        LOGGER.at(Level.WARNING).log("MCP request refused: %s", failure.name());
        respondJson(exchange, status,
            JsonRpc.error(null, JsonRpc.INVALID_REQUEST, failure.message()));
    }

    /** Handles a single request or a batch, returning null when nothing needs a reply. */
    @Nullable
    private JsonElement dispatch(JsonElement parsed, McpCaller caller) {
        if (parsed.isJsonArray()) {
            JsonArray requests = parsed.getAsJsonArray();
            if (requests.isEmpty()) {
                return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Empty batch");
            }
            JsonArray responses = new JsonArray();
            for (JsonElement element : requests) {
                if (!element.isJsonObject()) {
                    responses.add(JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Batch entry is not an object"));
                    continue;
                }
                JsonObject response = protocol.handle(element.getAsJsonObject(), caller);
                if (response != null) responses.add(response);
            }
            return responses.isEmpty() ? null : responses;
        }
        if (parsed.isJsonObject()) {
            return protocol.handle(parsed.getAsJsonObject(), caller);
        }
        return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Request must be an object or an array");
    }

    /**
     * Accepts requests with no {@code Origin} (a normal MCP client) or a loopback one, and refuses
     * anything else so a page in a browser cannot reach this endpoint.
     */
    static boolean isOriginAllowed(@Nullable String origin) {
        if (origin == null || origin.isBlank() || "null".equalsIgnoreCase(origin)) return true;
        try {
            String host = URI.create(origin.trim()).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Reads the body, or returns null when it is larger than the cap. */
    @Nullable
    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            return body.length > MAX_BODY_BYTES ? null : body;
        }
    }

    private static void respondJson(HttpExchange exchange, int status, JsonElement payload) throws IOException {
        respond(exchange, status, "application/json", payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void respondText(HttpExchange exchange, int status, String text) throws IOException {
        respond(exchange, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
        throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}

package app.unichat.server;

import app.unichat.bridge.BridgeStates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Where the bridges report their connection state ({@code homeserver.status_endpoint}): {@code POST /status/<bridge>}
 * on 127.0.0.1, authorized with that bridge's own as_token. Feeds {@link BridgeStates}.
 */
final class StatusEndpoint {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Bigger bodies aren't a bridge state. */
    private static final int MAX_BODY = 1 << 20;

    private final BridgeStates states;
    /** bridge → its as_token (null when unknown: then nothing from it is accepted). */
    private final Function<String, String> tokens;
    private HttpServer server;

    StatusEndpoint(BridgeStates states, Function<String, String> tokens) {
        this.states = states;
        this.tokens = tokens;
    }

    /** Starts on a free port (once); returns the URL for {@code bridge}'s config. */
    synchronized String url(String bridge) throws IOException {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
            server.createContext("/status/", this::handle);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bridge-status");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        }
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/status/" + bridge;
    }

    synchronized void stop() {
        if (server != null) server.stop(0);
        server = null;
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String bridge = ex.getRequestURI().getPath().substring("/status/".length());
            String expected = tokens.apply(bridge);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (expected == null || auth == null || !MessageDigest.isEqual(("Bearer " + expected).getBytes(StandardCharsets.UTF_8),
                    auth.getBytes(StandardCharsets.UTF_8))) {
                ex.sendResponseHeaders(401, -1);
                return;
            }
            JsonNode body;
            try (InputStream in = ex.getRequestBody()) {
                byte[] b = in.readNBytes(MAX_BODY);
                body = JSON.readTree(b);
            } catch (IOException e) {
                ex.sendResponseHeaders(400, -1);
                return;
            }
            if (body != null && body.isObject()) states.record(bridge, body, BridgeStates.now());
            ex.sendResponseHeaders(204, -1);
        } catch (RuntimeException e) {
            System.err.println("Bridge status not recorded: " + e);
        }
    }
}

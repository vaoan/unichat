package app.unichat.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Dropbox's API in memory, on this machine only: its token endpoint, files and upload sessions, answered as Dropbox does. */
final class FakeDropbox implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Map<String, Integer> revs = new ConcurrentHashMap<>();
    final Set<String> folders = ConcurrentHashMap.newKeySet();
    final Map<String, ByteArrayOutputStream> sessions = new ConcurrentHashMap<>();
    final AtomicInteger tokensMade = new AtomicInteger(), sessionCalls = new AtomicInteger();
    /** The PKCE challenge the browser was sent with, and the code Dropbox gives back for it. */
    volatile String challenge;
    private final HttpServer server;
    private final String appKey, refresh;
    private volatile String access = "none";

    FakeDropbox(String appKey, String refresh) throws IOException {
        this.appKey = appKey;
        this.refresh = refresh;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The short-lived token stops working (as after four hours). */
    void expire() {
        access = "expired-" + access;
    }

    /** Someone else (another computer) puts a file there. */
    void put(String path, byte[] data) {
        files.put(path, data);
        revs.merge(path, 1, Integer::sum);
    }

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            String path = x.getRequestURI().getPath();
            byte[] body = x.getRequestBody().readAllBytes();
            if (path.equals("/oauth2/token")) {
                token(x, Loopback.query(new String(body, StandardCharsets.UTF_8)));
                return;
            }
            if (!("Bearer " + access).equals(x.getRequestHeaders().getFirst("Authorization"))) {
                reply(x, 401, "{\"error_summary\": \"expired_access_token/\"}");
                return;
            }
            String argHeader = x.getRequestHeaders().getFirst("Dropbox-API-Arg");
            if (argHeader != null && !argHeader.chars().allMatch(c -> c < 128)) {
                reply(x, 400, "{\"error_summary\": \"header not ASCII\"}");
                return;
            }
            JsonNode arg = argHeader != null ? JSON.readTree(argHeader) : body.length == 0 || new String(body).equals("null")
                    ? JSON.nullNode() : JSON.readTree(body);
            switch (path) {
                case "/2/users/get_current_account" -> reply(x, 200, "{\"email\": \"yo@correo.com\"}");
                case "/2/files/get_metadata" -> {
                    String p = arg.path("path").asText();
                    if (files.containsKey(p)) reply(x, 200, "{\".tag\": \"file\", \"rev\": \"r" + revs.get(p) + "\"}");
                    else if (folders.contains(p)) reply(x, 200, "{\".tag\": \"folder\", \"id\": \"id:f\"}");
                    else reply(x, 409, "{\"error_summary\": \"path/not_found/..\"}");
                }
                case "/2/files/create_folder_v2" -> {
                    if (!folders.add(arg.path("path").asText())) reply(x, 409, "{\"error_summary\": \"path/conflict/folder/..\"}");
                    else reply(x, 200, "{}");
                }
                case "/2/files/upload" -> {
                    put(arg.path("path").asText(), body);
                    reply(x, 200, "{}");
                }
                case "/2/files/upload_session/start" -> {
                    sessionCalls.incrementAndGet();
                    String id = "s" + sessions.size();
                    sessions.computeIfAbsent(id, k -> new ByteArrayOutputStream()).write(body);
                    reply(x, 200, "{\"session_id\": \"" + id + "\"}");
                }
                case "/2/files/upload_session/append_v2", "/2/files/upload_session/finish" -> {
                    sessionCalls.incrementAndGet();
                    ByteArrayOutputStream s = sessions.get(arg.path("cursor").path("session_id").asText());
                    if (s == null || s.size() != arg.path("cursor").path("offset").asLong()) {
                        reply(x, 409, "{\"error_summary\": \"incorrect_offset/..\"}");
                        return;
                    }
                    s.write(body);
                    if (path.endsWith("finish")) put(arg.path("commit").path("path").asText(), s.toByteArray());
                    reply(x, 200, "{}");
                }
                case "/2/files/download" -> {
                    byte[] f = files.get(arg.path("path").asText());
                    if (f == null) reply(x, 409, "{\"error_summary\": \"path/not_found/..\"}");
                    else {
                        x.sendResponseHeaders(200, f.length);
                        x.getResponseBody().write(f);
                    }
                }
                default -> reply(x, 404, "{}");
            }
        }
    }

    /** A lasting permission from a code (checking PKCE), or a short-lived token from the lasting permission. */
    private void token(HttpExchange x, Map<String, String> form) throws IOException {
        if (!appKey.equals(form.get("client_id"))) {
            reply(x, 400, "{\"error\": \"invalid_client\"}");
            return;
        }
        if ("authorization_code".equals(form.get("grant_type"))) {
            boolean ok = "the-code".equals(form.get("code")) && challenge != null
                    && challenge.equals(DropboxLogin.challenge(form.getOrDefault("code_verifier", "")));
            if (!ok) reply(x, 400, "{\"error\": \"invalid_grant\", \"error_description\": \"code_verifier mismatch\"}");
            else reply(x, 200, "{\"access_token\": \"a0\", \"refresh_token\": \"" + refresh + "\", \"expires_in\": 14400}");
            return;
        }
        if (!refresh.equals(form.get("refresh_token"))) {
            reply(x, 400, "{\"error\": \"invalid_grant\"}");
            return;
        }
        access = "a" + tokensMade.incrementAndGet();
        reply(x, 200, "{\"access_token\": \"" + access + "\", \"expires_in\": 14400}");
    }

    private static void reply(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(code, b.length);
        x.getResponseBody().write(b);
    }

    @Override public void close() {
        server.stop(0);
    }
}

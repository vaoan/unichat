package app.unichat.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Dropbox's API in memory, on this machine only: its token endpoint, files and upload sessions, answered as Dropbox does. */
final class FakeDropbox implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Map<String, Integer> revs = new ConcurrentHashMap<>();
    final Set<String> folders = ConcurrentHashMap.newKeySet();
    final Map<String, ByteArrayOutputStream> sessions = new ConcurrentHashMap<>();
    final AtomicInteger tokensMade = new AtomicInteger();
    final AtomicInteger sessionCalls = new AtomicInteger();
    /** The PKCE challenge the browser was sent with, and the code Dropbox gives back for it. */
    volatile @Nullable String challenge;
    private final HttpServer server;
    private final String appKey;
    private final String refresh;
    private volatile String access = "none";

    FakeDropbox(String appKey, String refresh) throws IOException {
        this.appKey = appKey;
        this.refresh = refresh;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The short-lived token stops working (as after four hours). */
    @SuppressWarnings("NonAtomicVolatileUpdate") // only the test calls it, between requests
    void expire() {
        access = "expired-" + access;
    }

    /**
     * The names right in {@code dir}, as Dropbox gives them: the first one, then the rest after its cursor (the cursor is
     * the folder here), so taking only the first page loses names.
     */
    private void list(HttpExchange x, String dir, boolean rest) throws IOException {
        Set<String> names = new java.util.TreeSet<>();
        boolean there = folders.contains(dir);
        for (String f : files.keySet()) if (f.startsWith(dir + "/")) {
            names.add(f.substring(dir.length() + 1).split("/")[0]);
            there = true;
        }
        for (String d : folders) if (d.startsWith(dir + "/")) names.add(d.substring(dir.length() + 1).split("/")[0]);
        if (!there) {
            reply(x, 409, "{\"error_summary\": \"path/not_found/..\"}");
            return;
        }
        List<String> all = List.copyOf(names);
        List<String> page = rest ? all.subList(Math.min(1, all.size()), all.size()) : all.subList(0, Math.min(1, all.size()));
        com.fasterxml.jackson.databind.node.ObjectNode out = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode entries = out.putArray("entries");
        for (String n : page) entries.addObject().put("name", n);
        out.put("cursor", dir).put("has_more", !rest && all.size() > 1);
        reply(x, 200, JSON.writeValueAsString(out));
    }

    /** Whether anything is at {@code p}: a file, or a folder (made, or holding something, as on Dropbox). */
    private boolean there(String p) {
        return files.containsKey(p) || folders.contains(p) || files.keySet().stream().anyMatch(f -> f.startsWith(p + "/"))
            || folders.stream().anyMatch(d -> d.startsWith(p + "/"));
    }

    /** move_v2 without autorename: a file or a folder with all in it, never over what's there (to/conflict). */
    private void move(HttpExchange x, String from, String to) throws IOException {
        if (!there(from)) {
            reply(x, 409, "{\"error_summary\": \"from_lookup/not_found/..\"}");
            return;
        }
        if (there(to)) {
            reply(x, 409, "{\"error_summary\": \"to/conflict/file/..\"}");
            return;
        }
        for (String f : List.copyOf(files.keySet())) {
            if (!f.equals(from) && !f.startsWith(from + "/")) continue;
            String moved = to + f.substring(from.length());
            files.put(moved, files.remove(f));
            revs.put(moved, revs.getOrDefault(f, 1));
        }
        for (String d : List.copyOf(folders)) {
            if (!d.equals(from) && !d.startsWith(from + "/")) continue;
            folders.remove(d);
            folders.add(to + d.substring(from.length()));
        }
        reply(x, 200, "{\"metadata\": {}}");
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
                token(x, Loopback.query(new String(body, UTF_8)));
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
            JsonNode arg = argHeader != null ? JSON.readTree(argHeader) : body.length == 0 || new String(body, UTF_8).equals("null")
                    ? JSON.nullNode() : JSON.readTree(body);
            switch (path) {
                case "/2/users/get_current_account" -> reply(x, 200, "{\"email\": \"yo@correo.com\"}");
                case "/2/files/get_metadata" -> {
                    String p = arg.path("path").asText();
                    if (files.containsKey(p)) reply(x, 200, "{\".tag\": \"file\", \"rev\": \"r" + revs.get(p) + "\", \"size\": "
                            + files.get(p).length + ", \"content_hash\": \"" + contentHash(files.get(p)) + "\"}");
                    else if (folders.contains(p)) reply(x, 200, "{\".tag\": \"folder\", \"id\": \"id:f\"}");
                    else reply(x, 409, "{\"error_summary\": \"path/not_found/..\"}");
                }
                case "/2/files/list_folder" -> list(x, arg.path("path").asText(), false);
                case "/2/files/list_folder/continue" -> list(x, arg.path("cursor").asText(), true);
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
                    if (s == null) {
                        reply(x, 409, "{\"error_summary\": \"incorrect_offset/..\"}");
                        return;
                    }
                    synchronized (s) { // the offset checked, the part added and the file kept as one, as Dropbox does
                        if (s.size() != arg.path("cursor").path("offset").asLong()) {
                            reply(x, 409, "{\"error_summary\": \"incorrect_offset/..\"}");
                            return;
                        }
                        s.write(body);
                        if (path.endsWith("finish")) put(arg.path("commit").path("path").asText(), s.toByteArray());
                    }
                    reply(x, 200, "{}");
                }
                case "/2/files/delete_v2" -> {
                    String p = arg.path("path").asText();
                    boolean there = files.containsKey(p) || folders.contains(p) // a folder is there when anything is in it, as on Dropbox
                            || files.keySet().stream().anyMatch(f -> f.startsWith(p + "/")) || folders.stream().anyMatch(d -> d.startsWith(p + "/"));
                    if (!there) {
                        reply(x, 409, "{\"error_summary\": \"path_lookup/not_found/..\"}");
                        return;
                    }
                    files.remove(p);
                    files.keySet().removeIf(f -> f.startsWith(p + "/"));
                    folders.removeIf(d -> d.equals(p) || d.startsWith(p + "/"));
                    reply(x, 200, "{\"metadata\": {\".tag\": \"file\"}}");
                }
                case "/2/files/move_v2" -> move(x, arg.path("from_path").asText(), arg.path("to_path").asText());
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

    /** Dropbox's content hash, as its documentation describes it: SHA-256 of each 4 MB block's SHA-256. */
    static String contentHash(byte[] data) {
        try {
            java.security.MessageDigest all = java.security.MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < data.length; i += 4 * 1024 * 1024) {
                all.update(java.security.MessageDigest.getInstance("SHA-256").digest(
                        java.util.Arrays.copyOfRange(data, i, Math.min(data.length, i + 4 * 1024 * 1024))));
            }
            return java.util.HexFormat.of().formatHex(all.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(UTF_8);
        x.sendResponseHeaders(code, b.length);
        x.getResponseBody().write(b);
    }

    @Override public void close() {
        server.stop(0);
    }
}

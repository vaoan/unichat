package app.unichat.cloud;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** pCloud's API in memory, on this machine only: the methods Unichat calls, answered the way pCloud does. */
final class FakePCloud implements AutoCloseable {
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Map<String, Integer> versions = new ConcurrentHashMap<>();
    final Set<String> dirs = ConcurrentHashMap.newKeySet();
    private final HttpServer server;
    private final String token;

    FakePCloud(String token) throws IOException {
        this.token = token;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        dirs.add("/");
    }

    String host() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            String method = x.getRequestURI().getPath().substring(1);
            Map<String, String> q = query(x.getRequestURI().getRawQuery());
            byte[] body = x.getRequestBody().readAllBytes();
            if (method.startsWith("file/")) { // a download link
                byte[] f = files.get(method.substring("file".length()));
                x.sendResponseHeaders(f == null ? 404 : 200, f == null ? -1 : f.length);
                if (f != null) x.getResponseBody().write(f);
                return;
            }
            if (!("Bearer " + token).equals(x.getRequestHeaders().getFirst("Authorization"))) {
                json(x, "{\"result\": 2094, \"error\": \"Invalid 'access_token' provided.\"}");
                return;
            }
            switch (method) {
                case "userinfo" -> json(x, "{\"result\": 0, \"email\": \"yo@correo.com\"}");
                case "stat" -> {
                    String p = q.get("path");
                    if (files.containsKey(p)) json(x, "{\"result\": 0, \"metadata\": {\"hash\": " + versions.get(p)
                            + ", \"modified\": \"x\", \"size\": " + files.get(p).length + "}}");
                    else if (dirs.contains(p)) json(x, "{\"result\": 0, \"metadata\": {\"isfolder\": true}}");
                    else json(x, "{\"result\": 2009, \"error\": \"File not found.\"}");
                }
                case "checksumfile" -> {
                    byte[] f = files.get(q.get("path"));
                    if (f == null) json(x, "{\"result\": 2009, \"error\": \"File not found.\"}");
                    else json(x, "{\"result\": 0, \"sha1\": \"" + sha1(f) + "\"}");
                }
                case "createfolderifnotexists" -> {
                    String p = q.get("path");
                    if (!dirs.contains(p.substring(0, Math.max(1, p.lastIndexOf('/'))))) {
                        json(x, "{\"result\": 2005, \"error\": \"Directory does not exist.\"}");
                        return;
                    }
                    dirs.add(p);
                    json(x, "{\"result\": 0}");
                }
                case "uploadfile" -> {
                    String folder = q.get("path"), p = (folder.equals("/") ? "" : folder) + "/" + q.get("filename");
                    if (!dirs.contains(folder)) {
                        json(x, "{\"result\": 2005, \"error\": \"Directory does not exist.\"}");
                        return;
                    }
                    put(p, body);
                    json(x, "{\"result\": 0}");
                }
                case "getfilelink" -> {
                    String p = q.get("path");
                    if (!files.containsKey(p)) json(x, "{\"result\": 2009, \"error\": \"File not found.\"}");
                    else json(x, "{\"result\": 0, \"hosts\": [\"127.0.0.1:" + server.getAddress().getPort() + "\"], \"path\": \"/file"
                            + p.replace("\"", "") + "\"}");
                }
                default -> json(x, "{\"result\": 2000, \"error\": \"Unknown method.\"}");
            }
        }
    }

    /** Someone else (another computer) puts a file there. */
    void put(String path, byte[] data) {
        files.put(path, data);
        versions.merge(path, 1, Integer::sum);
    }

    static String sha1(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void json(HttpExchange x, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(200, b.length);
        x.getResponseBody().write(b);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw != null) for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) q.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    @Override public void close() {
        server.stop(0);
    }
}

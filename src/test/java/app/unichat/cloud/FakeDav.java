package app.unichat.cloud;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A WebDAV account in memory, on this machine only: what a test's "cloud" holds, and how many times it was asked. */
final class FakeDav implements AutoCloseable {
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Set<String> dirs = ConcurrentHashMap.newKeySet();
    final Map<String, Integer> versions = new ConcurrentHashMap<>();
    final AtomicInteger gets = new AtomicInteger(), puts = new AtomicInteger();
    /** Whether a listing gives whole addresses ("https://host/dav/…", as some servers do) rather than paths. */
    volatile boolean wholeHrefs;
    /** Paths whose next PUT fails (the network dropped), once each. */
    final Set<String> failOnce = ConcurrentHashMap.newKeySet();
    private final HttpServer server;
    private final String auth;

    FakeDav(String user, String secret) throws IOException {
        auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/dav";
    }

    /** Someone else (another computer) puts a file there. */
    void put(String path, byte[] data) {
        files.put(path, data);
        versions.merge(path, 1, Integer::sum);
    }

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            if (!auth.equals(x.getRequestHeaders().getFirst("Authorization"))) {
                x.sendResponseHeaders(401, -1);
                return;
            }
            String path = URLDecoder.decode(x.getRequestURI().getRawPath(), StandardCharsets.UTF_8).replaceFirst("^/dav/?", "").replaceFirst("/$", "");
            byte[] body = x.getRequestBody().readAllBytes();
            switch (x.getRequestMethod()) {
                case "PROPFIND" -> {
                    if (!path.isEmpty() && !files.containsKey(path) && !dirs.contains(path)) {
                        x.sendResponseHeaders(404, -1);
                        return;
                    }
                    StringBuilder listed = new StringBuilder();
                    if ("1".equals(x.getRequestHeaders().getFirst("Depth"))) {
                        for (String child : children(path)) {
                            listed.append("<d:response><d:href>").append(wholeHrefs ? url() + "/" : "/dav/").append(href(path.isEmpty() ? child : path + "/" + child))
                                    .append("</d:href></d:response>");
                        }
                    }
                    byte[] xml = ("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>"
                            + (wholeHrefs ? url() + "/" : "/dav/") + href(path)
                            + "/</d:href><d:propstat><d:prop>"
                            + (files.containsKey(path) ? "<d:getetag>\"" + versions.get(path) + "\"</d:getetag><d:getcontentlength>"
                                    + files.get(path).length + "</d:getcontentlength>" : "")
                            + "</d:prop></d:propstat></d:response>" + listed + "</d:multistatus>").getBytes(StandardCharsets.UTF_8);
                    x.sendResponseHeaders(207, xml.length);
                    x.getResponseBody().write(xml);
                }
                case "MKCOL" -> x.sendResponseHeaders(dirs.add(path) ? 201 : 405, -1);
                case "PUT" -> {
                    if (failOnce.remove(path)) {
                        x.sendResponseHeaders(503, -1);
                        return;
                    }
                    puts.incrementAndGet();
                    put(path, body);
                    x.sendResponseHeaders(201, -1);
                }
                case "GET" -> {
                    byte[] f = files.get(path);
                    if (f == null) {
                        x.sendResponseHeaders(404, -1);
                        return;
                    }
                    gets.incrementAndGet();
                    x.sendResponseHeaders(200, f.length);
                    x.getResponseBody().write(f);
                }
                case "DELETE" -> {
                    if (!files.containsKey(path) && !dirs.contains(path)) {
                        x.sendResponseHeaders(404, -1);
                        return;
                    }
                    files.remove(path);
                    files.keySet().removeIf(p -> p.startsWith(path + "/"));
                    dirs.removeIf(d -> d.equals(path) || d.startsWith(path + "/"));
                    x.sendResponseHeaders(204, -1);
                }
                default -> x.sendResponseHeaders(405, -1);
            }
        }
    }

    /** The names right in {@code dir}, as a server says them: each once. */
    private java.util.Set<String> children(String dir) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String p : files.keySet()) if (p.startsWith(dir + "/")) out.add(p.substring(dir.length() + 1).split("/")[0]);
        for (String p : dirs) if (p.startsWith(dir + "/")) out.add(p.substring(dir.length() + 1).split("/")[0]);
        return out;
    }

    /** {@code path} as an href: each part encoded, as servers write them. */
    private static String href(String path) {
        StringBuilder b = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            if (!b.isEmpty()) b.append('/');
            b.append(java.net.URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return b.toString();
    }

    @Override public void close() {
        server.stop(0);
    }
}

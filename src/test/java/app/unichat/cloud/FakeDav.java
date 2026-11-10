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
            String path = URLDecoder.decode(x.getRequestURI().getRawPath(), StandardCharsets.UTF_8).replaceFirst("^/dav/?", "");
            byte[] body = x.getRequestBody().readAllBytes();
            switch (x.getRequestMethod()) {
                case "PROPFIND" -> {
                    if (!path.isEmpty() && !files.containsKey(path) && !dirs.contains(path)) {
                        x.sendResponseHeaders(404, -1);
                        return;
                    }
                    byte[] xml = ("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response><d:propstat><d:prop>"
                            + (files.containsKey(path) ? "<d:getetag>\"" + versions.get(path) + "\"</d:getetag><d:getcontentlength>"
                                    + files.get(path).length + "</d:getcontentlength>" : "")
                            + "</d:prop></d:propstat></d:response></d:multistatus>").getBytes(StandardCharsets.UTF_8);
                    x.sendResponseHeaders(207, xml.length);
                    x.getResponseBody().write(xml);
                }
                case "MKCOL" -> x.sendResponseHeaders(dirs.add(path) ? 201 : 405, -1);
                case "PUT" -> {
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
                default -> x.sendResponseHeaders(405, -1);
            }
        }
    }

    @Override public void close() {
        server.stop(0);
    }
}

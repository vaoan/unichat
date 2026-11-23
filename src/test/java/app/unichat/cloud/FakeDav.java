package app.unichat.cloud;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.net.InetAddress;
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
    /** A listing of a file fails, as Dropbox's does (path/not_folder), rather than listing nothing. */
    volatile boolean filesAreNoFolders;
    /** Paths whose next GET fails (the network dropped), once each. */
    final Set<String> failReadOnce = ConcurrentHashMap.newKeySet();
    /** Paths whose next listing (PROPFIND, depth 1) fails, once each. */
    final Set<String> failListOnce = ConcurrentHashMap.newKeySet();
    /** Paths whose next MOVE fails, once each. */
    final Set<String> failMoveOnce = ConcurrentHashMap.newKeySet();
    /** Paths whose every GET fails (a file the server can't give), until taken out of here. */
    final Set<String> failReads = ConcurrentHashMap.newKeySet();
    /** Paths whose next DELETE fails, once each. */
    final Set<String> failDeleteOnce = ConcurrentHashMap.newKeySet();
    /** Paths whose next PUT waits until its latch is let go (a slow network), once each. */
    final Map<String, java.util.concurrent.CountDownLatch> holdOnce = new ConcurrentHashMap<>();
    /**
     * The next GET of a path waits for its latch: that request alone held (the server answers the others meanwhile), and
     * whatever thread asked it with it, holding what it holds (a round's vault part or a vault job holds the vault's lock).
     */
    final Map<String, java.util.concurrent.CountDownLatch> holdReadOnce = new ConcurrentHashMap<>();
    /** Paths whose held PUT or GET (above) let this latch go once it arrived and waits: the test knows the request is there. */
    final Map<String, java.util.concurrent.CountDownLatch> arrived = new ConcurrentHashMap<>();
    /** Each request as it was answered ("PUT path", "GET path"), in order: what came before what. */
    final java.util.List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Every request as it came ("PROPFIND path", "PUT path"…), held or not: what was asked, even what changes nothing. */
    final java.util.List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Paths that can't be read once a PUT of them lands (every GET answers a failure), until taken out of here. */
    final Set<String> unreadableAfterPut = ConcurrentHashMap.newKeySet();
    private final Set<String> unreadable = ConcurrentHashMap.newKeySet();
    /** Paths whose next PUT lands but answers a failure (the answer lost), once each. */
    final Set<String> failAfterOnce = ConcurrentHashMap.newKeySet();
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fake-dav");
        t.setDaemon(true);
        return t;
    });
    private final String auth;

    FakeDav(String user, String secret) throws IOException {
        auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(pool); // a request held never holds the others: as a real server, many at once
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/dav";
    }

    /** Someone else (another computer) puts a file there. */
    void put(String path, byte[] data) {
        files.put(path, data);
        versions.merge(path, 1, Integer::sum);
        for (int at = path.lastIndexOf('/'); at > 0; at = path.lastIndexOf('/', at - 1)) dirs.add(path.substring(0, at)); // as a server has them
    }

    /** Whether anything is at {@code path}: a file, or a folder (made, or holding something). */
    private boolean there(String path) {
        return files.containsKey(path) || dirs.contains(path) || files.keySet().stream().anyMatch(p -> p.startsWith(path + "/"));
    }

    /** MOVE (RFC 4918): a file or a folder with all in it to its Destination; with "Overwrite: F", never over what's there. */
    private void move(HttpExchange x, String path) throws IOException {
        String dest = URLDecoder.decode(java.net.URI.create(x.getRequestHeaders().getFirst("Destination")).getRawPath(), StandardCharsets.UTF_8)
            .replaceFirst("^/dav/?", "").replaceFirst("/$", "");
        if (failMoveOnce.remove(path)) {
            x.sendResponseHeaders(503, -1);
            return;
        }
        if (!there(path)) {
            x.sendResponseHeaders(404, -1);
            return;
        }
        if (there(dest) && "F".equals(x.getRequestHeaders().getFirst("Overwrite"))) {
            x.sendResponseHeaders(412, -1);
            return;
        }
        for (String p : java.util.List.copyOf(files.keySet())) {
            if (!p.equals(path) && !p.startsWith(path + "/")) continue;
            String to = dest + p.substring(path.length());
            files.put(to, files.remove(p));
            versions.put(to, versions.getOrDefault(p, 1));
        }
        for (String d : java.util.List.copyOf(dirs)) {
            if (!d.equals(path) && !d.startsWith(path + "/")) continue;
            dirs.remove(d);
            dirs.add(dest + d.substring(path.length()));
        }
        log.add("MOVE " + path + " " + dest);
        x.sendResponseHeaders(201, -1);
    }

    @SuppressFBWarnings(value = "POTENTIAL_XML_INJECTION", justification = "a fake server's page built from the test's own names")
    private void handle(HttpExchange x) throws IOException {
        try (x) {
            if (!auth.equals(x.getRequestHeaders().getFirst("Authorization"))) {
                x.sendResponseHeaders(401, -1);
                return;
            }
            String path = URLDecoder.decode(x.getRequestURI().getRawPath(), StandardCharsets.UTF_8).replaceFirst("^/dav/?", "").replaceFirst("/$", "");
            byte[] body = x.getRequestBody().readAllBytes();
            asked.add(x.getRequestMethod() + " " + path);
            switch (x.getRequestMethod()) {
                case "PROPFIND" -> {
                    if ("1".equals(x.getRequestHeaders().getFirst("Depth")) && failListOnce.remove(path)) {
                        x.sendResponseHeaders(503, -1);
                        return;
                    }
                    if (!path.isEmpty() && !files.containsKey(path) && !dirs.contains(path)) {
                        x.sendResponseHeaders(404, -1);
                        return;
                    }
                    if (filesAreNoFolders && files.containsKey(path) && "1".equals(x.getRequestHeaders().getFirst("Depth"))) {
                        x.sendResponseHeaders(409, -1);
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
                case "PUT" -> put(x, path, body);
                case "GET" -> get(x, path);
                case "DELETE" -> {
                    if (failDeleteOnce.remove(path)) {
                        x.sendResponseHeaders(503, -1);
                        return;
                    }
                    if (!files.containsKey(path) && !dirs.contains(path)) {
                        x.sendResponseHeaders(404, -1);
                        return;
                    }
                    files.remove(path);
                    files.keySet().removeIf(p -> p.startsWith(path + "/"));
                    dirs.removeIf(d -> d.equals(path) || d.startsWith(path + "/"));
                    x.sendResponseHeaders(204, -1);
                }
                case "MOVE" -> move(x, path);
                default -> x.sendResponseHeaders(405, -1);
            }
        }
    }

    /** A file written, unless held, failed or answered as lost (each once, as the test set it). */
    private void put(HttpExchange x, String path, byte[] body) throws IOException {
        waitIfHeld(holdOnce, path);
        if (failOnce.remove(path)) {
            x.sendResponseHeaders(503, -1);
            return;
        }
        puts.incrementAndGet();
        put(path, body);
        log.add("PUT " + path);
        if (unreadableAfterPut.contains(path)) unreadable.add(path);
        x.sendResponseHeaders(failAfterOnce.remove(path) ? 503 : 201, -1);
    }

    /** A file read, unless held or failed (each once), or unreadable since its last write. */
    private void get(HttpExchange x, String path) throws IOException {
        waitIfHeld(holdReadOnce, path);
        log.add("GET " + path);
        if (failReadOnce.remove(path) || failReads.contains(path)) {
            x.sendResponseHeaders(503, -1);
            return;
        }
        if (unreadable.contains(path) && unreadableAfterPut.contains(path)) {
            x.sendResponseHeaders(503, -1);
            return;
        }
        unreadable.remove(path);
        byte[] f = files.get(path);
        if (f == null) {
            x.sendResponseHeaders(404, -1);
            return;
        }
        gets.incrementAndGet();
        x.sendResponseHeaders(200, f.length);
        x.getResponseBody().write(f);
    }

    /** The request waits while {@code path} is held in {@code holds} (once), having said it arrived. */
    private void waitIfHeld(Map<String, java.util.concurrent.CountDownLatch> holds, String path) {
        java.util.concurrent.CountDownLatch held = holds.remove(path);
        if (held == null) return;
        arrivedAt(path);
        try {
            held.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void arrivedAt(String path) {
        java.util.concurrent.CountDownLatch a = arrived.remove(path);
        if (a != null) a.countDown();
    }

    /** The names right in {@code dir}, as a server says them: each once. */
    private Set<String> children(String dir) {
        Set<String> out = new java.util.TreeSet<>();
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
        pool.shutdownNow();
    }
}

package app.unichat.links;

import app.unichat.model.LinkMedia;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkInspectorTest {
    @TempDir Path dir;
    private HttpServer server;
    private String base;
    private final AtomicInteger hits = new AtomicInteger();
    private final LinkInspector open = new LinkInspector(a -> true);
    private final CountDownLatch over = new CountDownLatch(1);
    private final ExecutorService handlers = Executors.newCachedThreadPool();

    private void answer(String path, int code, String type, String body, String location) {
        server.createContext(path, ex -> {
            hits.incrementAndGet();
            if (type != null) ex.getResponseHeaders().add("Content-Type", type);
            if (location != null) ex.getResponseHeaders().add("Location", location);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        });
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        over.countDown();
        server.stop(0);
        handlers.shutdown();
    }

    private Optional<LinkMedia> inspect(LinkInspector i, String path) throws Exception {
        return i.inspect(base + path).get();
    }

    @Test
    void whatTheServerAnswersDecidesNotTheName() throws Exception {
        answer("/sin-extension", 200, "video/mp4; x=1", "12345", null);
        answer("/cancion", 200, "audio/mpeg", "1", null);
        answer("/falso.mp4", 200, "image/png", "1", null);
        answer("/generico.webm", 200, "application/octet-stream", "1", null);
        LinkMedia v = inspect(open, "/sin-extension").orElseThrow();
        assertEquals(LinkMedia.Kind.VIDEO, v.kind());
        assertEquals("video/mp4", v.mimeType());
        assertEquals(5, v.size());
        assertEquals(LinkMedia.Kind.AUDIO, inspect(open, "/cancion").orElseThrow().kind());
        assertFalse(inspect(open, "/falso.mp4").isPresent());
        assertEquals(LinkMedia.Kind.VIDEO, inspect(open, "/generico.webm").orElseThrow().kind());
    }

    @Test
    void aPageOffersTheMediaItDeclaresAndAPlayerPageIsNotMedia() throws Exception {
        answer("/clip.mp4", 200, "video/mp4", "1", null);
        answer("/reproductor", 200, "text/html", "<html></html>", null);
        answer("/post", 200, "text/html", "<meta property=\"og:video\" content=\"/reproductor\">"
                + "<meta property=\"og:video:secure_url\" content=\"/clip.mp4\">", null);
        answer("/solo-reproductor", 200, "text/html", "<meta property=\"og:video\" content=\"/reproductor\">", null);
        answer("/etiqueta", 200, "text/html", "<audio><source src=\"/a.ogg\"></audio>", null);
        answer("/a.ogg", 200, "audio/ogg", "1", null);
        assertEquals(base + "/clip.mp4", inspect(open, "/post").orElseThrow().url());
        assertFalse(inspect(open, "/solo-reproductor").isPresent());
        assertEquals(LinkMedia.Kind.AUDIO, inspect(open, "/etiqueta").orElseThrow().kind());
    }

    @Test
    void aPageThatNeverEndsIsGivenUpOnAtTheDeadline() throws Exception {
        // Headers and the start of a page, then nothing more until the test is over: a stranger's server trickling bytes.
        server.createContext("/goteo", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("<html><head>".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            try {
                over.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        assertFalse(inspect(new LinkInspector(a -> true, Duration.ZERO), "/goteo").isPresent());
    }

    @Test
    void aLinkIsOpenedOnce() throws Exception {
        answer("/uno", 200, "text/plain", "x", null);
        inspect(open, "/uno");
        inspect(open, "/uno");
        assertEquals(1, hits.get());
    }

    @Test
    void aLinkCantReachThisMachineEvenByRedirecting() throws Exception {
        answer("/secreto", 200, "video/mp4", "1", null);
        assertFalse(inspect(new LinkInspector(), "/secreto").isPresent(), "not directly");
        assertEquals(0, hits.get());

        // Only the first hop looks public: the redirect to this machine must never be followed.
        answer("/salta", 302, null, "", base + "/secreto");
        InetAddress local = InetAddress.getLoopbackAddress();
        AtomicInteger checks = new AtomicInteger();
        LinkInspector firstOnly = new LinkInspector(a -> a.equals(local) && checks.getAndIncrement() == 0);
        assertFalse(inspect(firstOnly, "/salta").isPresent());
        assertEquals(1, hits.get(), "the redirect, not the secret");
    }

    @Test
    void onlyTheOpenInternetIsPublic() throws Exception {
        for (String a : List.of("127.0.0.1", "10.0.0.1", "192.168.1.1", "172.16.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fe80::1", "fd00::1", "224.0.0.1",
                "198.18.0.1", "198.19.255.254", "240.0.0.1", "255.255.255.255", "192.0.0.8",
                "64:ff9b::7f00:1", "64:ff9b::a00:1", "2002:7f00:1::1", "2002:c0a8:101::1", "::127.0.0.1")) {
            assertFalse(LinkInspector.isPublic(InetAddress.getByName(a)), a);
        }
        for (String a : List.of("8.8.8.8", "2606:4700:4700::1111", "198.20.0.1", "192.0.2.1", "64:ff9b::808:808", "2002:808:808::1")) assertTrue(LinkInspector.isPublic(InetAddress.getByName(a)), a);
        assertFalse(open.inspect("file:///etc/passwd").get().isPresent());
        assertFalse(open.inspect("http://usuario:clave@ejemplo.org/").get().isPresent());
        assertNull(LinkInspector.kindOf("text/plain", "/a.mp4"));
    }

    @Test
    void aDownloadKeepsTheFileAndItsType() throws Exception {
        answer("/f", 200, "video/webm", "contenido", null);
        answer("/no", 404, "text/plain", "x", null);
        Path target = dir.resolve("f");
        assertEquals("video/webm", open.download(base + "/f", target));
        assertEquals("contenido", Files.readString(target));
        assertThrows(java.io.IOException.class, () -> open.download(base + "/no", dir.resolve("n")));
        assertThrows(java.io.IOException.class, () -> new LinkInspector().download(base + "/f", dir.resolve("m")));
    }
}

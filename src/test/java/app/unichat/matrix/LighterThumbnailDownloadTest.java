package app.unichat.matrix;

import app.unichat.Present;
import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Only what the server's /thumbnail made is turned into the lightest file; an original is never touched. */
class LighterThumbnailDownloadTest {
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile boolean thumbnailFails;
    private byte[] png;

    @BeforeEach
    @SuppressFBWarnings(value = "PREDICTABLE_RANDOM", justification = "seeded test data, the same on every run on purpose; nothing secret")
    void server() throws Exception {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(5);
        for (int y = 0; y < 200; y++) for (int x = 0; x < 200; x++) img.setRGB(x, y, r.nextInt(1 << 24));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "png", b);
        png = b.toByteArray();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    private void answer(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        if (thumbnailFails && ex.getRequestURI().getPath().contains("/thumbnail/")) {
            ex.sendResponseHeaders(500, -1);
            ex.close();
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "image/png");
        ex.sendResponseHeaders(200, png.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(png);
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private MediaDownloader media(ExecutorService io) {
        return new MediaDownloader(new MatrixClient("http://127.0.0.1:" + server.getAddress().getPort(), "t"), io);
    }

    private static String mxc() {
        return "mxc://example.org/lp" + System.nanoTime();
    }

    @Test
    void aThumbnailThatIsAnOpaquePngIsKeptAsAJpeg() throws Exception {
        try (ExecutorService io = Executors.newSingleThreadExecutor()) {
            var media = media(io);
            String url = mxc();
            Path first = media.media(url, 168, 168).get();
            String name = String.valueOf(first.getFileName());
            assertTrue(name.endsWith(".jpg"), first.toString());
            assertTrue(Files.size(first) < png.length);
            try (var same = Files.list(Present.of(first.getParent()))) {
                String hash = name.replace(".jpg", "");
                assertEquals(java.util.List.of(name), same
                        .map(f -> String.valueOf(f.getFileName())).filter(n -> n.startsWith(hash)).toList(),
                        "no .png ever carried the final name: only the file that stays");
            }
            int seen = requests.get();
            assertEquals(first, media.media(url, 168, 168).get());
            assertEquals(seen, requests.get(), "a cache hit: no second request");
        }
    }

    @Test
    void anOriginalIsNeverConverted() throws Exception {
        try (ExecutorService io = Executors.newSingleThreadExecutor()) {
            Path p = media(io).media(mxc(), 0, 0).get();
            assertTrue(p.getFileName().toString().endsWith(".png"), p.toString());
            assertArrayEquals(png, Files.readAllBytes(p));
        }
    }

    @Test
    void anOriginalFetchedWhenTheThumbnailFailedIsNeverConverted() throws Exception {
        thumbnailFails = true;
        try (ExecutorService io = Executors.newSingleThreadExecutor()) {
            Path p = media(io).media(mxc(), 168, 168).get();
            assertTrue(p.getFileName().toString().endsWith(".png"), p.toString());
            assertArrayEquals(png, Files.readAllBytes(p));
        }
    }

    @Test
    void aBridgesDirectMediaIsNeverConverted() throws Exception {
        // A bridge's port is fixed: take the first of them this computer leaves free, or skip.
        HttpServer bridge = null;
        String name = "";
        for (var e : app.unichat.bridge.BridgePorts.BRIDGE_PORTS.entrySet()) {
            try {
                bridge = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), e.getValue()), 0);
                name = e.getKey();
                break;
            } catch (BindException busy) {
                // try the next one
            }
        }
        assumeTrue(bridge != null, "every bridge's port is in use here");
        bridge = Present.of(bridge);
        bridge.createContext("/", this::answer);
        bridge.start();
        try (ExecutorService io = Executors.newSingleThreadExecutor()) {
            String url = "mxc://" + app.unichat.bridge.BridgePorts.mediaServerName(name) + "/d" + System.nanoTime();
            Path p = media(io).media(url, 168, 168).get();
            assertTrue(p.getFileName().toString().endsWith(".png"), p.toString());
            assertArrayEquals(png, Files.readAllBytes(p));
        } finally {
            bridge.stop(0);
        }
    }
}

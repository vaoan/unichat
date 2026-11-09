package app.unichat.gif;

import app.unichat.model.MediaLibrary;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GifFilesTest {
    @Test
    void savedGifKeepsAPreviewTheGridCanShow() {
        Gif g = new Gif("giphy", "1", "Gato", "https://m/200w.gif", "https://m/giphy.mp4", "https://m/giphy.gif", 480, 360);
        MediaLibrary.Item i = GifFiles.item(g);
        assertEquals("https://m/giphy.mp4", i.url());
        assertEquals("video/mp4", i.mime());
        assertEquals("https://m/200w.gif", GifFiles.preview(i), "an mp4 can't be shown as an Image: keep the GIF preview");
        assertEquals("Gato", i.info().path("title").asText());
    }

    @Test
    void aHangingDownloadGivesUp() throws Exception {
        CountDownLatch hang = new CountDownLatch(1); // the server answers only once the test is over
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            try { hang.await(); } catch (InterruptedException ignored) { }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        try {
            long t0 = System.currentTimeMillis();
            assertThrows(Exception.class, () -> GifFiles.download("http://127.0.0.1:" + server.getAddress().getPort() + "/g", ".gif",
                    Duration.ofMillis(500)));
            assertTrue(System.currentTimeMillis() - t0 < GifFiles.TIMEOUT.toMillis(), "the limit asked for, not the default");
        } finally {
            hang.countDown();
            server.stop(0);
        }
    }

    @Test
    void downloadedFileLivesInTheGifCacheFolder() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = "GIF89a".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            Path f = GifFiles.download("http://127.0.0.1:" + server.getAddress().getPort() + "/g", ".gif", GifFiles.TIMEOUT);
            assertTrue(Files.exists(f));
            assertEquals("gif-cache", f.getParent().getFileName().toString());
            assertFalse(f.getFileName().toString().contains("/"));
        } finally {
            server.stop(0);
        }
    }
}

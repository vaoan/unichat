package app.unichat.matrix;

import app.unichat.settings.Settings;
import app.unichat.support.Hashes;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stickers have a room of their own, apart from photos and videos, and the ones already downloaded move in. */
class StickerCacheTest {
    private HttpServer server;
    private final AtomicInteger downloads = new AtomicInteger();

    @BeforeEach
    void server() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            downloads.incrementAndGet();
            byte[] gif = "GIF89a-not-really".getBytes();
            ex.getResponseHeaders().add("Content-Type", "image/gif");
            ex.sendResponseHeaders(200, gif.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(gif);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Path cache(String folder) {
        return Settings.get().cacheDir().resolve(folder);
    }

    @Test
    void aStickerGoesToItsOwnRoomAndAPhotoToTheMediaOne() throws Exception {
        try (ExecutorService io = Executors.newSingleThreadExecutor()) {
            var media = new MediaDownloader(new MatrixClient("http://127.0.0.1:" + server.getAddress().getPort(), "t"), io);
            String id = "s" + System.nanoTime();
            List<Double> seen = new ArrayList<>();
            Path sticker = media.sticker("mxc://example.org/" + id, seen::add).get();
            assertEquals(cache("sticker-cache"), sticker.getParent());
            Path photo = media.media("mxc://example.org/p" + id, 0, 0).get();
            assertEquals(cache("media"), photo.getParent());
            assertEquals(sticker, media.sticker("mxc://example.org/" + id, p -> { }).get(), "kept: not downloaded again");
            assertEquals(2, downloads.get());
        }
    }

    @Test
    void aStickerTheMediaFolderHadMovesInWithoutDownloading() throws Exception {
        try (ExecutorService io = Executors.newSingleThreadExecutor()) {
            var media = new MediaDownloader(new MatrixClient("http://127.0.0.1:" + server.getAddress().getPort(), "t"), io);
            String url = "mxc://example.org/old" + System.nanoTime();
            Files.createDirectories(cache("media"));
            Path before = Files.writeString(cache("media").resolve(Hashes.sha256(MediaDownloader.cacheKey(url, 0, 0)) + ".gif"), "GIF");
            Path after = media.sticker(url, p -> { }).get();
            assertEquals(cache("sticker-cache"), after.getParent());
            assertFalse(Files.exists(before), "moved, not copied");
            assertTrue(Files.exists(after));
            assertEquals(0, downloads.get(), "already here: nothing downloaded");
        }
    }
}

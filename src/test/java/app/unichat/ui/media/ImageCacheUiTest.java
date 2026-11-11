package app.unichat.ui.media;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A picture decoded before its load was even registered: removing the finished load ran inside the map's own
 * {@code computeIfAbsent}, which refuses that ("Recursive update"), so the load failed and stayed, failed, for good.
 * A small picture on a fast machine did it (a custom reaction showed ✨ for ever on CI's Linux).
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ImageCacheUiTest {
    @TempDir Path dir;

    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get();
        Platform.setImplicitExit(false);
    }

    @Test
    void aPictureDecodedAtOnceLoadsAndTheNextAskGetsIt() throws Exception {
        Path png = dir.resolve("e.png");
        assertTrue(javax.imageio.ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), "png", png.toFile()));
        String url = "mxc://s/quick-" + System.nanoTime();
        // Decoding on the calling thread: done before the load is registered, as the race made it.
        assertNotNull(ImageCache.load(url, (u, w, h) -> CompletableFuture.completedFuture(png), Runnable::run).get(),
                "the picture, not a failed load");
        assertNotNull(ImageCache.load(url, (u, w, h) -> CompletableFuture.failedFuture(new AssertionError("asked again"))).get(),
                "kept, nothing left loading");
    }
}

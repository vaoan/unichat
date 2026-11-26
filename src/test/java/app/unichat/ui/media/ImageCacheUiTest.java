package app.unichat.ui.media;

import app.unichat.Present;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertNotNull(ImageCache.load(url, (u, w, h) -> CompletableFuture.completedFuture(png), ImageCache.Tier.SMALL, Runnable::run).get(),
                "the picture, not a failed load");
        assertNotNull(ImageCache.load(url, (u, w, h) -> CompletableFuture.failedFuture(new AssertionError("asked again")), ImageCache.Tier.SMALL).get(),
                "kept, nothing left loading");
    }

    private Path png(int px) throws java.io.IOException {
        Path png = dir.resolve("p" + px + ".png");
        if (!java.nio.file.Files.exists(png)) {
            assertTrue(javax.imageio.ImageIO.write(new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB), "png", png.toFile()));
        }
        return png;
    }

    @Test
    void eachTierIsDecodedAtItsSizeTimesTheScale() {
        assertEquals(44, ImageCache.decodePx(ImageCache.Tier.SMALL, 1));
        assertEquals(88, ImageCache.decodePx(ImageCache.Tier.SMALL, 2));
        assertEquals(168, ImageCache.decodePx(ImageCache.Tier.LARGE, 2));
        assertEquals(105, ImageCache.decodePx(ImageCache.Tier.LARGE, 1.25));
        assertEquals(44, ImageCache.decodePx(ImageCache.Tier.SMALL, 0.5));
        assertEquals(ImageCache.Tier.SMALL, ImageCache.Tier.forSize(44));
        assertEquals(ImageCache.Tier.LARGE, ImageCache.Tier.forSize(45));
    }

    @Test
    void aSmallLoadDecodesNoBiggerThanItsTier() throws Exception {
        String url = "mxc://s/small-" + System.nanoTime();
        Path big = png(168);
        var image = ImageCache.load(url, (u, w, h) -> CompletableFuture.completedFuture(big), ImageCache.Tier.SMALL, Runnable::run).get();
        assertNotNull(image);
        assertTrue(image.getWidth() <= ImageCache.decodePx(ImageCache.Tier.SMALL, ScreenScale.max()), "decoded at its tier's size");
        assertNull(ImageCache.get(url, ImageCache.Tier.LARGE), "the large tier is not decoded by a small load");
    }

    @Test
    void bothTiersAskTheSameThumbnail() throws Exception {
        String url = "mxc://s/both-" + System.nanoTime();
        Path big = png(168);
        java.util.List<String> asked = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        app.unichat.service.MediaSource svc = (u, w, h) -> {
            asked.add(u + " " + w + "x" + h);
            return CompletableFuture.completedFuture(big);
        };
        assertNotNull(ImageCache.load(url, svc, ImageCache.Tier.SMALL, Runnable::run).get());
        assertNotNull(ImageCache.load(url, svc, ImageCache.Tier.LARGE, Runnable::run).get());
        assertEquals(java.util.List.of(url + " 168x168", url + " 168x168"), asked, "one thumbnail size for both tiers");
    }

    @Test
    void theSmallTierKeepsAtMost500AndTheLarge60() throws Exception {
        ImageCache.clear();
        Path one = png(8);
        app.unichat.service.MediaSource svc = (u, w, h) -> CompletableFuture.completedFuture(one);
        for (int i = 0; i < 520; i++) {
            ImageCache.load("mxc://s/cap-" + i, svc, ImageCache.Tier.SMALL, Runnable::run).get();
        }
        for (int i = 0; i < 70; i++) {
            ImageCache.load("mxc://s/cap-" + i, svc, ImageCache.Tier.LARGE, Runnable::run).get();
        }
        assertEquals(500, ImageCache.size(ImageCache.Tier.SMALL));
        assertEquals(60, ImageCache.size(ImageCache.Tier.LARGE));
    }

    @Test
    void aWidePictureIsDecodedWithItsShortSideAtTheTiersSize() throws Exception {
        Path wide = dir.resolve("wide.png");
        assertTrue(javax.imageio.ImageIO.write(new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB), "png", wide.toFile()));
        String url = "mxc://s/wide-" + System.nanoTime();
        var image = ImageCache.load(url, (u, w, h) -> CompletableFuture.completedFuture(wide), ImageCache.Tier.SMALL, Runnable::run).get();
        assertNotNull(image);
        assertEquals(ImageCache.decodePx(ImageCache.Tier.SMALL, ScreenScale.max()), Math.min(image.getWidth(), image.getHeight()),
                "the avatar's square is filled by the short side, never stretched from less");
    }

    @Test
    void twoTiersLoadingAtOnceEachGetTheirOwnSize() throws Exception {
        String url = "mxc://s/tiers-" + System.nanoTime();
        Path big = png(168);
        CompletableFuture<Path> held = new CompletableFuture<>();
        app.unichat.service.MediaSource svc = (u, w, h) -> held;
        var small = ImageCache.load(url, svc, ImageCache.Tier.SMALL, Runnable::run);
        var large = ImageCache.load(url, svc, ImageCache.Tier.LARGE, Runnable::run);
        held.complete(big);
        assertNotNull(small.get());
        assertNotNull(large.get());
        double scale = ScreenScale.max();
        assertEquals(ImageCache.decodePx(ImageCache.Tier.SMALL, scale), Present.of(ImageCache.get(url, ImageCache.Tier.SMALL)).getWidth());
        assertEquals(ImageCache.decodePx(ImageCache.Tier.LARGE, scale), Present.of(ImageCache.get(url, ImageCache.Tier.LARGE)).getWidth());
    }
}

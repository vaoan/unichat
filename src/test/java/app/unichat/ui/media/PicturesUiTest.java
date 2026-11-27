package app.unichat.ui.media;

import javafx.application.Platform;
import javafx.scene.image.WritableImage;
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

/** The window let go while closed lets its pictures go too: every cache of decoded pictures is emptied at once. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class PicturesUiTest {
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
    void everyDecodedPictureIsLetGo() throws Exception {
        Path png = dir.resolve("p.png");
        assertTrue(javax.imageio.ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), "png", png.toFile()));
        String avatar = "mxc://s/avatar-" + System.nanoTime();
        assertNotNull(ImageCache.load(avatar, (u, w, h) -> CompletableFuture.completedFuture(png), ImageCache.Tier.SMALL, Runnable::run).get());
        WritableImage still = new WritableImage(4, 4);
        Stills.put("mxc://s/photo", 4, 4, still);
        AnimatedImage.decodePlain(png, 32, 32);
        MessageMedia.FILE_PREVIEWS.put("file|1", still);
        assertNotNull(ImageCache.any(avatar), "the avatar is kept");
        assertNotNull(AnimatedImage.decoded(png, 32, 32), "the picture is kept");

        Pictures.forget();

        assertNull(ImageCache.any(avatar), "avatars");
        assertNull(Stills.get("mxc://s/photo", 4, 4), "photos");
        assertNull(AnimatedImage.decoded(png, 32, 32), "animations and plain pictures");
        assertEquals(0, AnimatedImage.decodedBytes(), "the decoded cache counts nothing it no longer holds");
        assertTrue(MessageMedia.FILE_PREVIEWS.isEmpty(), "files' previews");
    }
}

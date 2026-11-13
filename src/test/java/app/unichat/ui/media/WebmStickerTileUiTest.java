package app.unichat.ui.media;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.ui.FxWait;
import app.unichat.video.Ffmpeg;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A favourite or a set's sticker that is a WebM (Telegram's video stickers) never loaded in the panel: its tile handed the
 * WebM to the picture decoder ("Invalid image") and offered to try again for ever. It's converted first, as in the chat.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class WebmStickerTileUiTest {
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
    void aWebmStickerLoadsAsAMovingPicture() throws Exception {
        Path ffmpeg = Ffmpeg.find();
        assumeTrue(ffmpeg != null, "no ffmpeg");
        Path webm = dir.resolve("s.webm");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=s=64x64:d=1:r=10",
                "-c:v", "libvpx-vp9", webm.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(p.waitFor() == 0, "this ffmpeg can't make a WebM");
        Message m = new Message("$w", "!c:demo", "@a:demo", "A", false, java.time.Instant.now());
        m.setType(MessageType.STICKER);
        m.setMediaUrl("mxc://demo/webm-" + System.nanoTime());
        m.setMimeType("video/webm");
        m.setSize(64, 64);
        ImageView view = FxWait.fx(ImageView::new);
        MessageMedia.loadMedia((url, w, h) -> CompletableFuture.completedFuture(webm), view, m, 128, 128).get();
        assertNotNull(FxWait.fx(view::getImage), "the sticker shows");
    }

    @Test
    void aFavouriteGifThatIsAnMp4LoadsAsAMovingPicture() throws Exception {
        // Telegram's and WhatsApp's GIFs are MP4s: saved as favourites, their tile is one too.
        Path ffmpeg = Ffmpeg.find();
        assumeTrue(ffmpeg != null, "no ffmpeg");
        Path mp4 = dir.resolve("g.mp4");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=s=64x64:d=1:r=10",
                "-pix_fmt", "yuv420p", "-an", mp4.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(p.waitFor() == 0, "this ffmpeg can't make an MP4");
        assertTrue(AnimatedImage.decode(mp4, 64, 64).animated(), "its frames, as a GIF's");
        Message m = new Message("$g", "!c:demo", "@a:demo", "A", false, java.time.Instant.now());
        m.setType(MessageType.IMAGE); // as a saved GIF's tile asks for it (MediaItems.message)
        m.setMediaUrl("mxc://demo/mp4-" + System.nanoTime());
        m.setMimeType("video/mp4");
        m.setSize(64, 64);
        ImageView view = FxWait.fx(ImageView::new);
        MessageMedia.loadMedia((url, w, h) -> CompletableFuture.completedFuture(mp4), view, m, 128, 128).get();
        assertNotNull(FxWait.fx(view::getImage), "the GIF shows");
    }
}

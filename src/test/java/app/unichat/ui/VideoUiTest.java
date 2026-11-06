package app.unichat.ui;

import app.unichat.service.MediaSource;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real playback of a real mp4. {@code ./mvnw test -Dtest=VideoUiTest -Dunichat.uiTests=true}. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class VideoUiTest {
    @TempDir Path dir;

    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c);
        Platform.runLater(t);
        return t.get(20, TimeUnit.SECONDS);
    }

    private Path mp4() throws Exception {
        Path f = dir.resolve("clip.mp4");
        var enc = org.jcodec.api.awt.AWTSequenceEncoder.createSequenceEncoder(f.toFile(), 25);
        for (int i = 0; i < 50; i++) {
            BufferedImage img = new BufferedImage(160, 120, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(i * 5, 80, 160));
            g.fillRect(0, 0, 160, 120);
            g.dispose();
            enc.encodeImage(img);
        }
        enc.finish();
        return f;
    }

    private static void await(Callable<Boolean> c, String what) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (fx(c)) return;
            Thread.sleep(100);
        }
        assertTrue(fx(c), what);
    }

    private Stage window(StackPane root) throws Exception {
        return fx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(root, 320, 240));
            s.show();
            return s;
        });
    }

    @Test
    void aGifPlaysMutedAndLoopsAndIsLetGoWhenItLeaves() throws Exception {
        Path clip = mp4();
        MediaSource source = (url, w, h) -> CompletableFuture.completedFuture(clip);
        StackPane root = fx(StackPane::new);
        Stage stage = window(root);
        InlineVideo[] video = new InlineVideo[1];
        fx(() -> {
            video[0] = new InlineVideo(source, "mxc://s/1", 160, 120, () -> new StackPane());
            root.getChildren().add(video[0]);
            return null;
        });
        await(() -> video[0].player() != null && video[0].player().getStatus() == MediaPlayer.Status.PLAYING, "plays");
        MediaPlayer player = video[0].player();
        assertTrue(player.isMute());
        assertEquals(MediaPlayer.INDEFINITE, player.getCycleCount());

        fx(() -> root.getChildren().remove(video[0]));
        await(() -> video[0].player() == null, "released");
        assertEquals(MediaPlayer.Status.DISPOSED, player.getStatus());
        fx(() -> { stage.close(); return null; });
    }

    @Test
    void whatCantPlayShowsTheFallbackInstead() throws Exception {
        Path bad = Files.writeString(dir.resolve("roto.mp4"), "esto no es un video");
        MediaSource source = (url, w, h) -> CompletableFuture.completedFuture(bad);
        StackPane root = fx(StackPane::new);
        Stage stage = window(root);
        Node fallback = fx(StackPane::new);
        InlineVideo[] video = new InlineVideo[1];
        fx(() -> {
            video[0] = new InlineVideo(source, "mxc://s/1", 160, 120, () -> fallback);
            root.getChildren().add(video[0]);
            return null;
        });
        await(() -> video[0].getChildren().contains(fallback), "fallback shown");
        assertNull(fx(() -> video[0].player()));
        fx(() -> { stage.close(); return null; });
    }

    @Test
    void theViewerPlaysAndClosingItLetsGo() throws Exception {
        Path clip = mp4();
        StackPane root = fx(StackPane::new);
        Stage owner = window(root);
        Stage viewer = fx(() -> VideoViewer.show(owner, CompletableFuture.completedFuture(clip), "clip", null, p -> { }));
        await(() -> viewer.getProperties().get(VideoViewer.PLAYER) instanceof MediaPlayer p && p.getStatus() == MediaPlayer.Status.PLAYING, "viewer plays");
        MediaPlayer player = (MediaPlayer) viewer.getProperties().get(VideoViewer.PLAYER);
        Thread.sleep(400);
        fx(() -> {
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(viewer.getScene().snapshot(null), null), "png",
                    Path.of(System.getProperty("java.io.tmpdir"), "unichat-video-viewer.png").toFile());
            return null;
        });
        fx(() -> { viewer.close(); owner.close(); return null; });
        await(() -> player.getStatus() == MediaPlayer.Status.DISPOSED, "released");
        assertFalse(fx(viewer::isShowing));
    }

    @Test
    void theViewerOffersTheSystemPlayerForWhatItCantPlay() throws Exception {
        Path bad = Files.writeString(dir.resolve("roto.mp4"), "esto no es un video");
        StackPane root = fx(StackPane::new);
        Stage owner = window(root);
        Stage viewer = fx(() -> VideoViewer.show(owner, CompletableFuture.completedFuture(bad), "roto", null, p -> { }));
        await(() -> viewer.getScene().getRoot().lookupAll(".primary-button").size() == 1, "offers another app");
        fx(() -> { viewer.close(); owner.close(); return null; });
    }

    @Test
    void aWebmStickerPlaysConverted() throws Exception {
        Path ffmpeg = app.unichat.video.Ffmpeg.find();
        org.junit.jupiter.api.Assumptions.assumeTrue(ffmpeg != null, "no ffmpeg");
        Path webm = dir.resolve("sticker.webm");
        Process make = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi",
                "-i", "testsrc=s=128x128:d=1:r=10,format=yuva420p", "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", webm.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(make.waitFor(30, TimeUnit.SECONDS) && make.exitValue() == 0, "can't make a WebM");

        app.unichat.model.Message m = new app.unichat.model.Message("$1", "!c", "@a:s", "A", false, java.time.Instant.EPOCH);
        app.unichat.matrix.TestMessages.apply(m, "m.sticker", "{\"body\":\"s\",\"url\":\"mxc://s/1\",\"info\":{\"mimetype\":\"video/webm\",\"w\":128,\"h\":128}}");
        assertTrue(m.playsInline());
        MediaSource source = (url, w, h) -> CompletableFuture.completedFuture(webm);
        StackPane root = fx(StackPane::new);
        Stage stage = window(root);
        Node node = fx(() -> {
            MessageMedia media = new MessageMedia(source, s -> { }, s -> { }, () -> null, stage::getScene, new AudioPlayback(source, s -> { }));
            Node n = media.inlineVideoNode(m);
            root.getChildren().add(n);
            return n;
        });
        await(() -> node.lookup(".image-view") instanceof javafx.scene.image.ImageView v && v.getImage() != null, "the converted sticker shows");
        fx(() -> { stage.close(); return null; });
    }

    /** A real GIF: bridges deliver one for a sticker they still label video/webm. */
    private Path gif() throws Exception {
        Path f = dir.resolve("disfrazado.gif");
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        assertTrue(javax.imageio.ImageIO.write(img, "gif", f.toFile()));
        return f;
    }

    private static app.unichat.model.Message webmSticker() throws Exception {
        app.unichat.model.Message m = new app.unichat.model.Message("$1", "!c", "@a:s", "A", false, java.time.Instant.EPOCH);
        app.unichat.matrix.TestMessages.apply(m, "m.sticker", "{\"body\":\"s\",\"url\":\"mxc://s/1\",\"info\":{\"mimetype\":\"video/webm\",\"w\":64,\"h\":64}}");
        return m;
    }

    @Test
    void aStickerLabelledWebmThatIsReallyAGifShowsWithoutAnyConverter() throws Exception {
        Path file = gif();
        System.setProperty("unichat.ffmpeg", dir.resolve("no-existe").toString());
        try {
            MediaSource source = (url, w, h) -> CompletableFuture.completedFuture(file);
            StackPane root = fx(StackPane::new);
            Stage stage = window(root);
            app.unichat.model.Message m = webmSticker();
            Node node = fx(() -> {
                MessageMedia media = new MessageMedia(source, s -> { }, s -> { }, () -> null, stage::getScene, new AudioPlayback(source, s -> { }));
                Node n = media.inlineVideoNode(m);
                root.getChildren().add(n);
                return n;
            });
            await(() -> node.lookup(".image-view") instanceof javafx.scene.image.ImageView v && v.getImage() != null, "the GIF shows");
            fx(() -> { stage.close(); return null; });
        } finally {
            System.clearProperty("unichat.ffmpeg");
        }
    }

    @Test
    void theViewerShowsAPictureEvenIfItWasCalledAVideo() throws Exception {
        Path file = gif();
        StackPane root = fx(StackPane::new);
        Stage owner = window(root);
        Stage viewer = fx(() -> VideoViewer.show(owner, CompletableFuture.completedFuture(file), "s", null, p -> { }));
        await(() -> viewer.getScene().getRoot().lookup(".image-view") instanceof javafx.scene.image.ImageView v && v.getImage() != null, "shows it");
        assertTrue(fx(() -> viewer.getScene().getRoot().lookupAll(".primary-button").isEmpty()), "no format complaint");
        fx(() -> { viewer.close(); owner.close(); return null; });
    }

    @Test
    void aVideoJavaFxCantReadIsConvertedAndPlays() throws Exception {
        Path ffmpeg = app.unichat.video.Ffmpeg.find();
        org.junit.jupiter.api.Assumptions.assumeTrue(ffmpeg != null, "no ffmpeg");
        Path webm = dir.resolve("video.webm");
        Process make = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=s=160x120:d=1:r=10",
                "-c:v", "libvpx-vp9", webm.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(make.waitFor(30, TimeUnit.SECONDS) && make.exitValue() == 0, "can't make a WebM");
        StackPane root = fx(StackPane::new);
        Stage owner = window(root);
        Stage viewer = fx(() -> VideoViewer.show(owner, CompletableFuture.completedFuture(webm), "video", null, p -> { }));
        await(() -> viewer.getProperties().get(VideoViewer.PLAYER) instanceof MediaPlayer p && p.getStatus() == MediaPlayer.Status.PLAYING, "plays converted");
        fx(() -> { viewer.close(); owner.close(); return null; });
    }

    @Test
    void theClockReadsLikeAClock() {
        assertEquals("0:00", VideoViewer.clock(0));
        assertEquals("1:05", VideoViewer.clock(65));
        assertEquals("10:05", VideoViewer.clock(605));
        assertEquals("1:02:05", VideoViewer.clock(3725));
        assertEquals("0:00", VideoViewer.clock(Double.NaN));
    }
}

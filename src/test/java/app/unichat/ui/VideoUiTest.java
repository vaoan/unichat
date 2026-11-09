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
        up.get();
        Platform.setImplicitExit(false);
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }

    private Path mp4() throws Exception {
        return mp4(160, 120);
    }

    private Path mp4(int width, int height) throws Exception {
        Path f = dir.resolve("clip-" + width + "x" + height + ".mp4");
        var enc = org.jcodec.api.awt.AWTSequenceEncoder.createSequenceEncoder(f.toFile(), 25);
        for (int i = 0; i < 50; i++) {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(i * 5, 80, 160));
            g.fillRect(0, 0, width, height);
            g.dispose();
            enc.encodeImage(img);
        }
        enc.finish();
        return f;
    }

    private static app.unichat.model.MediaLibrary library() throws Exception {
        return new app.unichat.model.MediaLibrary(Files.createTempDirectory("unichat-library").resolve("media-library.json"));
    }

    private static void await(Callable<Boolean> c, String what) throws Exception {
        FxWait.until(what, c);
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
        await(() -> player.getCurrentTime().greaterThan(javafx.util.Duration.ZERO), "a frame on screen");
        FxWait.settled();
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
    void theViewerKeysChangeSpeedJumpToTheEndAndPlayAgain() throws Exception {
        Path clip = mp4();
        StackPane root = fx(StackPane::new);
        Stage owner = window(root);
        Stage viewer = fx(() -> VideoViewer.show(owner, CompletableFuture.completedFuture(clip), "clip", () -> { }, p -> { }));
        await(() -> viewer.getProperties().get(VideoViewer.PLAYER) instanceof MediaPlayer p && p.getStatus() == MediaPlayer.Status.PLAYING, "viewer plays");
        MediaPlayer player = (MediaPlayer) viewer.getProperties().get(VideoViewer.PLAYER);
        double rate = fx(player::getRate);
        key(viewer, javafx.scene.input.KeyCode.PERIOD, ">");
        assertEquals(VideoControls.nextRate(rate, 1), fx(player::getRate), 0.001, "> speeds up");
        key(viewer, javafx.scene.input.KeyCode.COMMA, "<");
        key(viewer, javafx.scene.input.KeyCode.END, "");
        await(() -> viewer.getScene().getRoot().lookup(".video-replay") != null, "watch again shows at the end");
        key(viewer, javafx.scene.input.KeyCode.K, "k");
        await(() -> viewer.getScene().getRoot().lookup(".video-replay") == null && player.getStatus() == MediaPlayer.Status.PLAYING,
                "K plays it again");
        assertTrue(fx(() -> viewer.getScene().getRoot().lookupAll(".icon-button").stream()
                .anyMatch(n -> "Pantalla completa (F)".equals(n.getAccessibleText()))), "full screen button");
        fx(() -> { viewer.close(); owner.close(); return null; });
    }

    @Test
    void aTallVideoLeavesRoomForTheControls() throws Exception {
        Path clip = mp4(240, 1600); // a phone video, taller than the window
        StackPane root = fx(StackPane::new);
        Stage owner = window(root);
        Stage viewer = fx(() -> VideoViewer.show(owner, CompletableFuture.completedFuture(clip), "clip", () -> { }, p -> { }));
        FxWait.until("the video plays", () -> viewer.getProperties().get(VideoViewer.PLAYER) instanceof MediaPlayer p
                && p.getStatus() == MediaPlayer.Status.PLAYING);
        FxWait.settled();
        String where = fx(() -> {
            javafx.scene.Node bar = viewer.getScene().getRoot().lookup(".video-controls");
            javafx.geometry.Bounds b = bar.localToScene(bar.getBoundsInLocal());
            return b.getMinY() + ".." + b.getMaxY() + " in " + viewer.getScene().getHeight();
        });
        assertTrue(fx(() -> {
            javafx.scene.Node bar = viewer.getScene().getRoot().lookup(".video-controls");
            javafx.geometry.Bounds b = bar.localToScene(bar.getBoundsInLocal());
            return b.getHeight() > 0 && b.getMaxY() <= viewer.getScene().getHeight() + 0.5;
        }), "the controls are inside the window: " + where);
        fx(() -> { viewer.close(); owner.close(); return null; });
    }

    @Test
    void fullScreenFillsTheScreen() throws Exception {
        Path clip = mp4();
        StackPane root = fx(StackPane::new);
        Stage owner = window(root);
        Stage viewer = fx(() -> VideoViewer.show(owner, CompletableFuture.completedFuture(clip), "clip", () -> { }, p -> { }));
        FxWait.until("the video plays", () -> viewer.getProperties().get(VideoViewer.PLAYER) instanceof MediaPlayer p
                && p.getStatus() == MediaPlayer.Status.PLAYING);
        key(viewer, javafx.scene.input.KeyCode.F, "f"); // an owned window hung right here on macOS
        double screenWidth = fx(() -> javafx.stage.Screen.getScreensForRectangle(viewer.getX(), viewer.getY(), 1, 1).get(0).getBounds().getWidth());
        FxWait.until("the window fills the screen (" + screenWidth + " wide)",
                () -> viewer.isFullScreen() && viewer.getWidth() >= screenWidth - 1);
        fx(() -> { viewer.setFullScreen(false); return null; });
        FxWait.until("back to a window", () -> !viewer.isFullScreen());
        fx(() -> { viewer.close(); owner.close(); return null; });
    }

    @Test
    void repeatingKeepsTheSpeed() throws Exception {
        Path clip = mp4();
        StackPane root = fx(StackPane::new);
        Stage owner = window(root);
        Stage viewer = fx(() -> VideoViewer.show(owner, CompletableFuture.completedFuture(clip), "clip", () -> { }, p -> { }));
        await(() -> viewer.getProperties().get(VideoViewer.PLAYER) instanceof MediaPlayer p && p.getStatus() == MediaPlayer.Status.PLAYING, "viewer plays");
        MediaPlayer player = (MediaPlayer) viewer.getProperties().get(VideoViewer.PLAYER);
        fx(() -> {
            viewer.getScene().getRoot().lookupAll(".icon-button").stream()
                    .filter(n -> n.getAccessibleText() != null && n.getAccessibleText().startsWith("Repetir"))
                    .findFirst().map(n -> (javafx.scene.control.Button) n).orElseThrow().fire();
            return null;
        });
        key(viewer, javafx.scene.input.KeyCode.PERIOD, ">");
        key(viewer, javafx.scene.input.KeyCode.PERIOD, ">");
        key(viewer, javafx.scene.input.KeyCode.PERIOD, ">");
        double rate = fx(player::getRate);
        assertTrue(rate >= 1.75, "sped up: " + rate);
        // Wait for it to start over (the clip is 2 s) and get a little into the new round, then see how fast its time
        // goes: how long the clock takes to play 0.6 s of the clip. A speed is clip time over wall time.
        double[] from = new double[1];
        long[] t0 = new long[1];
        await(() -> {
            double now = player.getCurrentTime().toSeconds();
            if (player.getCurrentCount() < 1 || now < 0.2 || now > 1) return false;
            from[0] = now;
            t0[0] = System.nanoTime();
            return true;
        }, "it starts over");
        double[] played = new double[1];
        long[] t1 = new long[1];
        await(() -> {
            played[0] = player.getCurrentTime().toSeconds() - from[0];
            t1[0] = System.nanoTime();
            return played[0] >= 0.6;
        }, "0.6 s of the new round played");
        double wall = (t1[0] - t0[0]) / 1e9;
        fx(() -> { viewer.close(); owner.close(); return null; });
        assertTrue(played[0] / wall > 1.4, "after starting over it still plays at " + rate + "x, not " + played[0] / wall);
    }

    private static void key(Stage stage,javafx.scene.input.KeyCode code, String text) throws Exception {
        fx(() -> {
            javafx.scene.Node target = stage.getScene().getFocusOwner() != null ? stage.getScene().getFocusOwner() : stage.getScene().getRoot();
            javafx.event.Event.fireEvent(target, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                    "", text, code, false, false, false, false));
            return null;
        });
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
        org.junit.jupiter.api.Assumptions.assumeTrue(make.waitFor() == 0, "can't make a WebM");

        app.unichat.model.Message m = new app.unichat.model.Message("$1", "!c", "@a:s", "A", false, java.time.Instant.EPOCH);
        app.unichat.matrix.TestMessages.apply(m, "m.sticker", "{\"body\":\"s\",\"url\":\"mxc://s/1\",\"info\":{\"mimetype\":\"video/webm\",\"w\":128,\"h\":128}}");
        assertTrue(m.playsInline());
        MediaSource source = (url, w, h) -> CompletableFuture.completedFuture(webm);
        StackPane root = fx(StackPane::new);
        Stage stage = window(root);
        Node node = fx(() -> {
            MessageMedia media = new MessageMedia(source, s -> { }, s -> { }, library(), stage::getScene, new AudioPlayback(source, s -> { }));
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
                MessageMedia media = new MessageMedia(source, s -> { }, s -> { }, library(), stage::getScene, new AudioPlayback(source, s -> { }));
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
        org.junit.jupiter.api.Assumptions.assumeTrue(make.waitFor() == 0, "can't make a WebM");
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

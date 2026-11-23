package app.unichat.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A file sent as a moving picture: a video, GIF or Lottie sticker made an animated WebP that plays by itself. */
class MovingPicturesTest {
    @TempDir Path dir;

    /** A video made by ffmpeg's own test source: {@code extra} adds sound or changes the format. */
    private Path video(String name, double seconds, String... extra) throws IOException, InterruptedException {
        Path ffmpeg = Ffmpeg.find();
        assumeTrue(ffmpeg != null, "no ffmpeg");
        assumeTrue(AnimatedWebp.img2webp() != null, "no img2webp");
        Path out = dir.resolve(name);
        java.util.List<String> c = new java.util.ArrayList<>(java.util.List.of(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi",
                "-i", "testsrc=s=960x540:d=" + seconds + ":r=30"));
        c.addAll(java.util.List.of(extra));
        c.add(out.toString());
        Process p = new ProcessBuilder(c).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(p.waitFor() == 0, "this ffmpeg can't make " + name);
        return out;
    }

    private static void animatedWebp(Path f) throws IOException {
        String b = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
        assertTrue(b.startsWith("RIFF") && b.startsWith("WEBP", 8), "a WebP");
        assertTrue(b.contains("ANIM"), "it moves");
    }

    @Test
    void aVideoWithSoundBecomesAMovingPictureWithoutItAndSmaller() throws Exception {
        Path mp4 = video("clip.mp4", 2, "-f", "lavfi", "-i", "sine=d=2", "-pix_fmt", "yuv420p", "-shortest");
        Path webp = MovingPictures.toWebp(mp4, dir.resolve("out.webp"), p -> { });
        animatedWebp(webp);
        int[] wh = app.unichat.support.ImageHeaders.size(webp);
        assertEquals(MovingPictures.SIDE, Math.max(wh[0], wh[1]), "its longest side made " + MovingPictures.SIDE + " px: " + wh[0] + "x" + wh[1]);
    }

    @Test
    void aGifAndAWebmBecomeOneToo() throws Exception {
        animatedWebp(MovingPictures.toWebp(video("a.gif", 1), dir.resolve("gif.webp"), p -> { }));
        animatedWebp(MovingPictures.toWebp(video("a.webm", 1, "-c:v", "libvpx-vp9"), dir.resolve("webm.webp"), p -> { }));
    }

    @Test
    void aLottieStickerBecomesOne() throws Exception {
        assumeTrue(LottieStickersTest.tool() != null, "no lottieconverter");
        assumeTrue(AnimatedWebp.img2webp() != null, "no img2webp");
        animatedWebp(MovingPictures.toWebp(LottieStickersTest.tgs(dir, LottieStickersTest.json()), dir.resolve("l.webp"), p -> { }));
    }

    /** Each frame is written whole to disk before the WebP is made: a long video would fill it. */
    @Test
    void aVideoLongerThanAMovingPictureIsRefusedSayingSo() throws Exception {
        Path mp4 = video("long.mp4", MovingPictures.MAX_SECONDS + 2, "-pix_fmt", "yuv420p");
        IOException e = assertThrows(IOException.class, () -> MovingPictures.toWebp(mp4, dir.resolve("long.webp"), p -> { }));
        assertTrue(e.getMessage().contains(Integer.toString(MovingPictures.MAX_SECONDS)), e.getMessage());
    }

    @Test
    void aFileThatIsntAVideoNorAnAnimationIsRefusedSayingSo() throws Exception {
        Path text = Files.writeString(dir.resolve("notes.txt"), "hola");
        IOException e = assertThrows(IOException.class, () -> MovingPictures.toWebp(text, dir.resolve("x.webp"), p -> { }));
        assertEquals(app.unichat.support.I18n.t("video.movingUnsupported"), e.getMessage());
    }
}

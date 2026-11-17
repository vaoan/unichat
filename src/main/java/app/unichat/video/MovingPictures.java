package app.unichat.video;

import app.unichat.support.I18n;
import app.unichat.support.ImageHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.DoubleConsumer;

/**
 * A file sent as a moving picture ("Imagen en movimiento"): a video, GIF or animated sticker made an animated WebP,
 * without sound, which Discord and WhatsApp play by themselves where a video waits to be pressed. What it is is told by
 * its bytes; ffmpeg reads it only as that format ({@link AnimatedWebp.Source}), never probing a stranger's file.
 */
public final class MovingPictures {
    /** Its longest side: a picture in a chat, not a video to watch full screen. */
    static final int SIDE = 480;
    /** Every frame is written whole before the WebP is made: 30 s at 15 a second is ~230 MB at most at 480 px. */
    static final int MAX_SECONDS = 30;
    private static final int FPS = 15, QUALITY = 75;
    private static final AnimatedWebp.Source GIF = new AnimatedWebp.Source("gif", false, true),
            MATROSKA = new AnimatedWebp.Source("matroska", false, false);

    private MovingPictures() {}

    /**
     * {@code file} as an animated WebP at {@code out}: an animated WebP as it is, a Lottie sticker drawn
     * ({@link LottieStickers}), a video or GIF through ffmpeg. Blocking; an IOException saying why in the person's words
     * when it can't be one (not a video, too long, no ffmpeg).
     */
    public static Path toWebp(Path file, Path out, DoubleConsumer installProgress) throws IOException, InterruptedException {
        String picture = ImageHeaders.pictureMime(file);
        if ("image/webp".equals(picture)) return Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
        if (LottieStickers.isLottie(file)) return LottieStickers.toWebp(file, out, installProgress);
        AnimatedWebp.Source src = "image/gif".equals(picture) ? GIF : ImageHeaders.isWebm(file) ? AnimatedWebp.Source.WEBM
                : quickTime(file) ? AnimatedWebp.Source.MP4 : null;
        if (src == null) throw new IOException(I18n.t("video.movingUnsupported"));
        Path ffmpeg = Ffmpeg.whenNeeded(installProgress);
        if (ffmpeg == null) throw new WebmStickers.NoConverter();
        Path img2webp = AnimatedWebp.img2webp();
        if (img2webp == null) throw new IOException("img2webp isn't there");
        try {
            return make(ffmpeg, img2webp, file, src, out);
        } catch (IOException e) {
            if (src != AnimatedWebp.Source.WEBM) throw e;
            return make(ffmpeg, img2webp, file, MATROSKA, out); // a Matroska video that isn't VP9 (an .mkv in H.264)
        }
    }

    private static Path make(Path ffmpeg, Path img2webp, Path file, AnimatedWebp.Source src, Path out)
            throws IOException, InterruptedException {
        AnimatedWebp.Probe probe = AnimatedWebp.probe(ffmpeg, file, src);
        if (probe.seconds() > MAX_SECONDS) throw new IOException(I18n.t("video.movingTooLong", MAX_SECONDS));
        AnimatedWebp.make(ffmpeg, img2webp, file, src, out, FPS, SIDE, QUALITY, probe);
        return out;
    }

    /** An MP4 or a QuickTime movie: the ISO box {@code ftyp} at byte 4 (a HEIC photo has it too, and ffmpeg then fails). */
    private static boolean quickTime(Path file) throws IOException {
        if (ImageHeaders.isVideo(file)) return true;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(8);
            return head.length == 8 && "ftyp".equals(new String(head, 4, 4, StandardCharsets.US_ASCII));
        }
    }
}

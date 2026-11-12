package app.unichat.video;

import app.unichat.support.I18n;
import app.unichat.support.Threads;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.DoubleConsumer;

/**
 * Video stickers (WebM, Telegram's) for other networks: as an animated WebP with their transparency ({@link #toWebp}).
 * And the other way: an animated GIF sticker as the WebM Telegram takes ({@link #fromGif}). Shown in Unichat they're
 * decoded straight into frames ({@code video/VideoFrames}), with no file made.
 */
public final class WebmStickers {
    /** Conversions and the first install, one at a time: never on the FX thread. */
    public static final ExecutorService WORKER = Executors.newSingleThreadExecutor(Threads.daemon("video-convert"));
    static final long TIMEOUT_SECONDS = 60;
    /** A sticker is seconds long and small: a file claiming more isn't one, and it shouldn't cost more than one. */
    private static final String MAX_SECONDS = "30";
    private static final String MAX_SIDE = "512";
    /** Telegram's video stickers: VP9 with transparency, 512 px on the long side, at most 3 s and 30 fps, no sound. */
    private static final String TELEGRAM_SIDE = "512", TELEGRAM_SECONDS = "3", TELEGRAM_FPS = "30";

    private WebmStickers() {}

    /** Thrown when there's no ffmpeg to convert with (none installed, and installing isn't possible or is off). */
    public static final class NoConverter extends IOException {
        NoConverter() { super(I18n.t("video.noConverter")); }
    }

    /**
     * {@code webm} as an animated WebP at {@code out}, its transparency kept (smooth edges, where a GIF's are all or
     * nothing) and lighter than a GIF ({@link AnimatedWebp}). Blocking; {@link NoConverter} without ffmpeg, an
     * IOException without img2webp or when it can't be made.
     */
    public static Path toWebp(Path webm, Path out, DoubleConsumer installProgress) throws IOException, InterruptedException {
        Path ffmpeg = Ffmpeg.whenNeeded(installProgress);
        if (ffmpeg == null) throw new NoConverter();
        Path img2webp = AnimatedWebp.img2webp();
        if (img2webp == null) throw new IOException("img2webp isn't there");
        AnimatedWebp.make(ffmpeg, img2webp, webm, AnimatedWebp.Source.WEBM, out, Integer.parseInt(TELEGRAM_FPS),
                Integer.parseInt(MAX_SIDE), 80, AnimatedWebp.probe(ffmpeg, webm, AnimatedWebp.Source.WEBM));
        return out;
    }

    /** {@code gif} as a Telegram video sticker at {@code out}. Blocking; {@link NoConverter} without ffmpeg. */
    public static Path fromGif(Path gif, Path out, DoubleConsumer installProgress) throws IOException, InterruptedException {
        Path ffmpeg = Ffmpeg.whenNeeded(installProgress);
        if (ffmpeg == null) throw new NoConverter();
        if (PartFile.firstOf(out, TIMEOUT_SECONDS, List.of(into -> fromGifCommand(ffmpeg, gif, into))) == null) {
            throw new IOException(I18n.t("video.stickerFailed"));
        }
        return out;
    }

    static List<String> fromGifCommand(Path ffmpeg, Path gif, Path out) {
        return List.of(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error",
                // The file comes from other people: only itself may be read, as what it claims to be.
                "-protocol_whitelist", "file", "-f", "gif", "-i", gif.toString(), "-t", TELEGRAM_SECONDS, "-an",
                "-vf", "scale=" + TELEGRAM_SIDE + ":" + TELEGRAM_SIDE + ":force_original_aspect_ratio=decrease,fps=" + TELEGRAM_FPS,
                "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-b:v", "0", "-crf", "40", "-f", "webm", out.toString());
    }

}

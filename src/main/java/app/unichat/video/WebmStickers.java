package app.unichat.video;

import app.unichat.support.ConvertedFiles;
import app.unichat.support.Processes;
import app.unichat.support.I18n;
import app.unichat.support.Threads;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.DoubleConsumer;

/**
 * Stickers that came as WebM (Telegram's), which JavaFX can't play, as an animated WebP or GIF, which Unichat can. The
 * result stays beside the original in the media cache, so its limit covers it and it's converted once. And the other
 * way: an animated GIF sticker as the WebM Telegram takes ({@link #fromGif}).
 */
public final class WebmStickers {
    /** Conversions and the first install, one at a time: never on the FX thread. */
    public static final ExecutorService WORKER = Executors.newSingleThreadExecutor(Threads.daemon("video-convert"));
    static final long TIMEOUT_SECONDS = 60;
    /** A sticker is seconds long and small: a file claiming more isn't one, and it shouldn't cost more than one. */
    private static final String MAX_SECONDS = "30";
    private static final String MAX_SIDE = "512";
    private static final String WEBP = "webp";
    private static final List<String> FORMATS = List.of(WEBP, "gif");
    /** Telegram's video stickers: VP9 with transparency, 512 px on the long side, at most 3 s and 30 fps, no sound. */
    private static final String TELEGRAM_SIDE = "512", TELEGRAM_SECONDS = "3", TELEGRAM_FPS = "30";

    private WebmStickers() {}

    /** Thrown when there's no ffmpeg to convert with (none installed, and installing isn't possible or is off). */
    public static final class NoConverter extends IOException {
        NoConverter() { super(I18n.t("video.noConverter")); }
    }

    /** The animated picture of {@code webm}: a WebP, or a GIF when this ffmpeg can't write those. Blocking. */
    public static Path animated(Path webm, DoubleConsumer installProgress) throws IOException, InterruptedException {
        for (String format : FORMATS) {
            Path done = convertedPath(webm, format);
            if (ConvertedFiles.reuse(done)) return done;
        }
        Path ffmpeg = Ffmpeg.whenNeeded(installProgress);
        if (ffmpeg == null) throw new NoConverter();
        return convert(ffmpeg, webm);
    }

    static Path convertedPath(Path webm, String format) {
        return ConvertedFiles.beside(webm, format);
    }

    /**
     * Best first. With libvpx before ffmpeg's own VP9 decoder, which drops the transparency (a sticker would come in a
     * box); WebP before GIF, whose edges are rougher, but not every ffmpeg writes it (Homebrew's doesn't).
     */
    private static Path convert(Path ffmpeg, Path webm) throws IOException, InterruptedException {
        for (boolean libvpx : new boolean[]{true, false}) {
            for (String format : FORMATS) {
                Path out = convertedPath(webm, format);
                if (PartFile.firstOf(out, TIMEOUT_SECONDS, List.of(into -> command(ffmpeg, webm, into, format, libvpx))) != null) {
                    return out;
                }
            }
        }
        throw new IOException(I18n.t("video.stickerFailed"));
    }

    /**
     * {@code webm} as an animated GIF at {@code out}, for networks that show a WebM as a video to press play on (Discord).
     * Blocking; {@link NoConverter} without ffmpeg.
     */
    public static Path toGif(Path webm, Path out, DoubleConsumer installProgress) throws IOException, InterruptedException {
        Path ffmpeg = Ffmpeg.whenNeeded(installProgress);
        if (ffmpeg == null) throw new NoConverter();
        if (PartFile.firstOf(out, TIMEOUT_SECONDS, List.of(into -> command(ffmpeg, webm, into, "gif", true),
                into -> command(ffmpeg, webm, into, "gif", false))) == null) {
            throw new IOException(I18n.t("video.stickerFailed") + " (" + ffmpegSays(ffmpeg, webm, out) + ")");
        }
        return out;
    }

    /** What ffmpeg says when the GIF can't be made (its last line, or how it ended), for the log. */
    private static String ffmpegSays(Path ffmpeg, Path webm, Path out) throws InterruptedException {
        Path probe = out.resolveSibling(out.getFileName() + ".why.gif");
        try {
            Processes.Result r = Processes.capture(new ProcessBuilder(command(ffmpeg, webm, probe, "gif", true))
                    .redirectErrorStream(true), TIMEOUT_SECONDS);
            return (r.finished() ? "exit " + r.exit() : "timed out") + ": " + Processes.lastLines(r.out(), 1).strip();
        } catch (IOException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // a leftover in the stickers folder
            }
        }
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
        double seconds = AnimatedWebp.probe(ffmpeg, webm, AnimatedWebp.Source.WEBM).seconds();
        AnimatedWebp.make(ffmpeg, img2webp, webm, AnimatedWebp.Source.WEBM, out, Integer.parseInt(TELEGRAM_FPS),
                Integer.parseInt(MAX_SIDE), 80, Math.min(seconds, Integer.parseInt(MAX_SECONDS)));
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

    static List<String> command(Path ffmpeg, Path webm, Path out, String format, boolean libvpx) {
        List<String> c = new ArrayList<>(List.of(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error",
                // The file comes from other people: only itself may be read, as what it claims to be.
                "-protocol_whitelist", "file", "-f", "matroska"));
        if (libvpx) c.addAll(List.of("-c:v", "libvpx-vp9"));
        c.addAll(List.of("-i", webm.toString(), "-t", MAX_SECONDS, "-an"));
        String scale = "scale=w='min(" + MAX_SIDE + ",iw)':h=-2";
        if (format.equals(WEBP)) c.addAll(List.of("-vf", scale, "-c:v", "libwebp_anim", "-q:v", "75"));
        else c.addAll(List.of("-vf", scale + ",split[a][b];[a]palettegen=reserve_transparent=1[p];[b][p]paletteuse"));
        c.addAll(List.of("-loop", "0", "-f", format, out.toString()));
        return c;
    }

}

package app.unichat.video;

import app.unichat.support.I18n;
import app.unichat.support.Processes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A short video's frames, decoded by ffmpeg straight into memory at the size they're shown (RGBA, transparency kept):
 * how Telegram's video stickers (WebM), which JavaFX can't play, are shown. No file in between: each was made a GIF of
 * up to 21 MB first, seconds each, and decoded again.
 */
public final class VideoFrames {
    private VideoFrames() {}

    private static final long TIMEOUT_SECONDS = 60;

    /**
     * What a video is to be decoded as: its size, frames a second and length ({@link AnimatedWebp.Probe}). Blocking;
     * {@link WebmStickers.NoConverter} without ffmpeg.
     */
    public static AnimatedWebp.Probe probe(Path video) throws IOException, InterruptedException {
        return AnimatedWebp.probe(ffmpeg(), video, sourceOf(video));
    }

    /** What {@code video} is read as, from its own bytes: a WebM (video stickers) or else an MP4 (GIFs sent as video). */
    static AnimatedWebp.Source sourceOf(Path video) throws IOException {
        return app.unichat.support.ImageHeaders.isWebm(video) ? AnimatedWebp.Source.WEBM : AnimatedWebp.Source.MP4;
    }

    /** Takes each frame as it's decoded: RGBA, {@code width}x{@code height}; the array is reused for the next one. */
    @FunctionalInterface
    public interface Frame {
        void take(byte[] rgba);
    }

    /**
     * {@code video}'s frames at {@code width}x{@code height}, at most {@code maxFrames} (fewer a second when it has more),
     * handed to {@code each} one at a time, never all held here: they were, and with the output's own buffer the peak
     * was three times the frames. How long each stays, in ms. Blocking; an IOException when ffmpeg can't read it.
     */
    public static int read(Path video, AnimatedWebp.Probe probe, int width, int height, int maxFrames, Frame each)
            throws IOException, InterruptedException {
        double natural = probe.fps() > 0 ? probe.fps() : 30;
        double fps = probe.seconds() > 0 && probe.seconds() * natural > maxFrames ? Math.max(1, maxFrames / probe.seconds()) : natural;
        byte[] frame = new byte[width * height * 4];
        int[] count = {0};
        Processes.read(new ProcessBuilder(command(ffmpeg(), video, sourceOf(video), width, height, fps, maxFrames))
                .redirectError(ProcessBuilder.Redirect.DISCARD), TIMEOUT_SECONDS, out -> {
            while (count[0] < maxFrames && out.readNBytes(frame, 0, frame.length) == frame.length) {
                each.take(frame);
                count[0]++;
            }
        });
        if (count[0] == 0) throw new IOException(I18n.t("video.stickerFailed"));
        return (int) Math.round(1000 / fps);
    }

    /**
     * ffmpeg decoding {@code in} to raw RGBA on its output, read only as {@code src} says (a WebM by libvpx, which keeps
     * the transparency; an MP4 by ffmpeg's own decoders).
     */
    static List<String> command(Path ffmpeg, Path in, AnimatedWebp.Source src, int width, int height, double fps, int maxFrames) {
        List<String> c = new java.util.ArrayList<>(List.of(ffmpeg.toString(), "-nostdin", "-loglevel", "error",
                "-protocol_whitelist", "file", "-f", src.format()));
        if (src.libvpx()) c.addAll(List.of("-c:v", "libvpx-vp9"));
        c.addAll(List.of("-i", in.toString(), "-an",
                "-vf", String.format(java.util.Locale.ROOT, "fps=%.3f,scale=%d:%d:flags=lanczos", fps, width, height),
                "-frames:v", Integer.toString(maxFrames), "-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1"));
        return c;
    }

    private static Path ffmpeg() throws IOException, InterruptedException {
        Path ffmpeg = Ffmpeg.whenNeeded(p -> { });
        if (ffmpeg == null) throw new WebmStickers.NoConverter();
        return ffmpeg;
    }
}

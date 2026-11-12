package app.unichat.video;

import app.unichat.support.I18n;
import app.unichat.support.Processes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A short video's frames, decoded by ffmpeg straight into memory at the size they're shown (RGBA, transparency kept):
 * how Telegram's video stickers (WebM), which JavaFX can't play, are shown. No file in between: each was made a GIF of
 * up to 21 MB first, seconds each, and decoded again.
 */
public final class VideoFrames {
    private VideoFrames() {}

    private static final long TIMEOUT_SECONDS = 60;

    /** {@code count} frames of {@code width}x{@code height}, RGBA, {@code delayMs} each. */
    public record Frames(int width, int height, int delayMs, List<byte[]> rgba) {}

    /**
     * What a video is to be decoded as: its size, frames a second and length ({@link AnimatedWebp.Probe}). Blocking;
     * {@link WebmStickers.NoConverter} without ffmpeg.
     */
    public static AnimatedWebp.Probe probe(Path video) throws IOException, InterruptedException {
        return AnimatedWebp.probe(ffmpeg(), video, AnimatedWebp.Source.WEBM);
    }

    /**
     * {@code video}'s frames at {@code width}x{@code height}, at most {@code maxFrames} (fewer a second when it has more).
     * Blocking; an IOException when ffmpeg can't read it.
     */
    public static Frames read(Path video, AnimatedWebp.Probe probe, int width, int height, int maxFrames)
            throws IOException, InterruptedException {
        double natural = probe.fps() > 0 ? probe.fps() : 30;
        double fps = probe.seconds() > 0 && probe.seconds() * natural > maxFrames ? Math.max(1, maxFrames / probe.seconds()) : natural;
        byte[] all = Processes.bytes(new ProcessBuilder(command(ffmpeg(), video, width, height, fps, maxFrames))
                .redirectError(ProcessBuilder.Redirect.DISCARD), TIMEOUT_SECONDS);
        List<byte[]> frames = new ArrayList<>();
        int size = width * height * 4;
        for (int at = 0; at + size <= all.length && frames.size() < maxFrames; at += size) {
            frames.add(java.util.Arrays.copyOfRange(all, at, at + size));
        }
        if (frames.isEmpty()) throw new IOException(I18n.t("video.stickerFailed"));
        return new Frames(width, height, (int) Math.round(1000 / fps), frames);
    }

    /** ffmpeg decoding {@code in} (read as a WebM only, by libvpx, which keeps the transparency) to raw RGBA on its output. */
    static List<String> command(Path ffmpeg, Path in, int width, int height, double fps, int maxFrames) {
        return List.of(ffmpeg.toString(), "-nostdin", "-loglevel", "error", "-protocol_whitelist", "file", "-f", "matroska",
                "-c:v", "libvpx-vp9", "-i", in.toString(), "-an",
                "-vf", String.format(java.util.Locale.ROOT, "fps=%.3f,scale=%d:%d:flags=lanczos", fps, width, height),
                "-frames:v", Integer.toString(maxFrames), "-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1");
    }

    private static Path ffmpeg() throws IOException, InterruptedException {
        Path ffmpeg = Ffmpeg.whenNeeded(p -> { });
        if (ffmpeg == null) throw new WebmStickers.NoConverter();
        return ffmpeg;
    }
}

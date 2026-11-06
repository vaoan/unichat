package app.unichat.video;

import app.unichat.support.Hashes;
import app.unichat.support.Os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Videos JavaFX can't read (VP9, AV1, HEVC where the system lacks it) as H.264 and AAC, which it can. Beside the original
 * in the media cache, so its limit covers the result and it's converted once.
 */
public final class VideoTranscoder {
    private static final long TIMEOUT_SECONDS = 15 * 60;

    private VideoTranscoder() {}

    /** Blocking, as long as the video takes to convert. */
    public static Path toPlayable(Path video, DoubleConsumer installProgress) throws IOException, InterruptedException {
        Path out = video.resolveSibling("converted-" + Hashes.sha256(video.getFileName().toString()) + ".mp4");
        if (Files.isRegularFile(out)) {
            out.toFile().setLastModified(System.currentTimeMillis());
            return out;
        }
        Path ffmpeg = Ffmpeg.whenNeeded(installProgress);
        if (ffmpeg == null) throw new WebmStickers.NoConverter();
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        try {
            for (String encoder : encoders()) {
                if (Ffmpeg.run(command(ffmpeg, video, tmp, encoder), TIMEOUT_SECONDS)) {
                    Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return out;
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        throw new IOException("No se pudo convertir el video");
    }

    /** The Mac's hardware encoder is much faster, and every full ffmpeg has libx264. */
    private static List<String> encoders() {
        return Os.mac() ? List.of("h264_videotoolbox", "libx264") : List.of("libx264");
    }

    static List<String> command(Path ffmpeg, Path video, Path out, String encoder) {
        List<String> c = new ArrayList<>(List.of(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error",
                // The file comes from other people: nothing but itself may be read.
                "-protocol_whitelist", "file", "-i", video.toString(), "-map", "0:v:0", "-map", "0:a:0?",
                // H.264 needs even sizes and this pixel format to play everywhere.
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", "-pix_fmt", "yuv420p", "-c:v", encoder));
        c.addAll(encoder.equals("libx264") ? List.of("-preset", "veryfast", "-crf", "23") : List.of("-b:v", "4M"));
        c.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", "-f", "mp4", out.toString()));
        return c;
    }
}

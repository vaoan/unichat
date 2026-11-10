package app.unichat.video;

import app.unichat.support.ConvertedFiles;
import app.unichat.support.I18n;
import app.unichat.support.Processes;
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
        Path out = ConvertedFiles.beside(video, "mp4");
        if (ConvertedFiles.reuse(out)) return out;
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
        throw new IOException(I18n.t("video.convertFailed"));
    }

    private static final java.util.regex.Pattern STREAM = java.util.regex.Pattern.compile("Stream #\\S+.*?: (Video|Audio): (\\w+)");

    /**
     * Whether what ffmpeg says of a file (its "Stream #0:0: Video: h264 …" lines) is what every computer plays: H.264
     * video, AAC or MP3 sound (or none). VP9, AV1, HEVC, Opus… aren't (a Mac doesn't open VP9).
     */
    static boolean playsEverywhere(String ffmpegInfo) {
        java.util.regex.Matcher m = STREAM.matcher(ffmpegInfo);
        boolean any = false;
        while (m.find()) {
            any = true;
            String codec = m.group(2);
            if (m.group(1).equals("Video") ? !codec.equals("h264") && !codec.equals("mjpeg") && !codec.equals("png")
                    : !codec.equals("aac") && !codec.equals("mp3")) return false;
        }
        return any;
    }

    /**
     * {@code file} (a video saved from a link) as H.264 and AAC, in its place, when it isn't already: same name, ".mp4".
     * Whole or not at all: the converted copy replaces it only once finished. The file it ends up as.
     */
    public static Path makePlayable(Path ffmpeg, Path file) throws IOException, InterruptedException {
        Processes.Result info = Processes.capture(new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-nostdin",
                "-protocol_whitelist", "file", "-i", file.toString()).redirectErrorStream(true), TIMEOUT_SECONDS);
        if (playsEverywhere(info.out())) return file;
        String name = file.getFileName().toString();
        Path out = file.resolveSibling((name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name) + ".mp4");
        Path tmp = file.resolveSibling("." + out.getFileName() + ".part");
        try {
            for (String encoder : encoders()) {
                if (Ffmpeg.run(command(ffmpeg, file, tmp, encoder), TIMEOUT_SECONDS)) {
                    Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    if (!out.equals(file)) Files.deleteIfExists(file);
                    return out;
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        throw new IOException(I18n.t("video.convertFailed"));
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

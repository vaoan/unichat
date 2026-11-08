package app.unichat.video;

import app.unichat.support.ConvertedFiles;
import app.unichat.support.I18n;
import app.unichat.support.Os;
import app.unichat.support.Processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * A smaller copy of a big still picture, made in another process. Decoding a phone's 24-megapixel WebP in Java takes
 * 600–900 MB (TwelveMonkeys keeps every macroblock of the frame, and asking it for a subsampled picture doesn't help),
 * which ran Unichat out of memory and froze its window while someone sent hundreds of them. macOS's own {@code sips}
 * does it in a third of a second; elsewhere ffmpeg ({@link Ffmpeg}). Kept beside the original in the media cache.
 */
public final class BigPictures {
    /** The longest side of the copy: what the photo viewer shows at most, and plenty for the timeline. */
    public static final int SIDE = 1600;
    private static final long TIMEOUT_SECONDS = 60;

    private BigPictures() {}

    /** The PNG copy of {@code picture} fitting {@value #SIDE} px, made once. Blocking. */
    public static Path shrunk(Path picture) throws IOException, InterruptedException {
        Path out = ConvertedFiles.beside(picture, SIDE + ".png");
        if (ConvertedFiles.reuse(out)) return out;
        Path tmp = out.resolveSibling(out.getFileName() + ".part.png");
        try {
            for (List<String> command : commands(picture, tmp)) {
                if (Processes.quiet(command, null, TIMEOUT_SECONDS).ok() && Files.size(tmp) > 0) {
                    Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return out;
                }
            }
        } catch (IOException e) {
            // Neither could: said below.
        } finally {
            Files.deleteIfExists(tmp);
        }
        throw new IOException(I18n.t("image.tooBig"));
    }

    /** sips first on a Mac (always there, no download); ffmpeg, the person's own or the one Unichat installs. */
    private static List<List<String>> commands(Path in, Path out) throws IOException, InterruptedException {
        List<List<String>> all = new java.util.ArrayList<>();
        if (Os.mac()) all.add(sips(in, out));
        Path ffmpeg = Ffmpeg.whenNeeded(p -> { });
        if (ffmpeg != null) all.add(ffmpeg(ffmpeg, in, out));
        return all;
    }

    static List<String> sips(Path in, Path out) {
        return List.of("/usr/bin/sips", "-s", "format", "png", "-Z", Integer.toString(SIDE), in.toString(), "--out", out.toString());
    }

    static List<String> ffmpeg(Path ffmpeg, Path in, Path out) {
        return List.of(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error",
                // The file comes from other people: only itself may be read.
                "-protocol_whitelist", "file", "-i", in.toString(),
                "-vf", "scale=" + SIDE + ":" + SIDE + ":force_original_aspect_ratio=decrease", "-frames:v", "1", out.toString());
    }
}

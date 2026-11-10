package app.unichat.video;

import app.unichat.support.ConvertedFiles;
import app.unichat.support.I18n;

import java.io.IOException;
import java.nio.file.Path;
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
        return shrunk(picture, Ffmpeg.WHEN_NEEDED);
    }

    /** {@code ffmpeg}: asked for only when sips can't (there's none, or it failed). */
    static Path shrunk(Path picture, Ffmpeg.Source ffmpeg) throws IOException, InterruptedException {
        Path out = ConvertedFiles.beside(picture, SIDE + ".png");
        if (ConvertedFiles.reuse(out)) return out;
        try {
            if (PartFile.firstOf(out, TIMEOUT_SECONDS, PartFile.sipsThenFfmpeg(into -> sips(picture, into), ffmpeg,
                    (found, into) -> ffmpeg(found, picture, into))) != null) return out;
        } catch (IOException e) {
            // Neither could: said below.
        }
        throw new IOException(I18n.t("image.tooBig"));
    }

    static List<String> sips(Path in, Path out) {
        return List.of(PartFile.SIPS.toString(), "-s", "format", "png", "-Z", Integer.toString(SIDE), in.toString(), "--out", out.toString());
    }

    static List<String> ffmpeg(Path ffmpeg, Path in, Path out) throws IOException {
        List<String> c = new java.util.ArrayList<>(List.of(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error"));
        c.addAll(Ffmpeg.input(in)); // the file comes from other people
        c.addAll(List.of("-vf", "scale=" + SIDE + ":" + SIDE + ":force_original_aspect_ratio=decrease", "-frames:v", "1",
                out.toString()));
        return c;
    }
}

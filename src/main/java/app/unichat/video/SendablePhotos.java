package app.unichat.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pictures sent "as a photo" (SendAs.PHOTO): what a network shows as a photo rather than a file. Telegram's bridge sends
 * as a file any picture over ~16.7 megapixels (image_as_file_pixels) or 10 MB, or 10,000 px of width plus height,
 * which is every 24-megapixel phone photo; WebP and HEIC are no photo anywhere. Those become a JPEG of at most
 * {@value #SIDE} px, the size Telegram and WhatsApp keep anyway, made in another process like {@link BigPictures}.
 */
public final class SendablePhotos {
    /** The longest side of the JPEG. */
    public static final int SIDE = 2560;
    static final long MAX_PIXELS = 16_000_000;
    static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long TIMEOUT_SECONDS = 60;

    private SendablePhotos() {}

    /** Whether a picture of {@code mime} can be made a photo: GIFs stay animated, and an SVG is a drawing. */
    public static boolean convertible(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return m.startsWith("image/") && !m.equals("image/gif") && !m.contains("svg");
    }

    /** Whether it already goes as a photo as it is: a JPEG or PNG within those limits ({@code width} 0: unknown). */
    public static boolean alreadyPhoto(String mime, int width, int height, long bytes) {
        boolean format = "image/jpeg".equalsIgnoreCase(mime) || "image/png".equalsIgnoreCase(mime);
        return format && width > 0 && height > 0 && (long) width * height <= MAX_PIXELS && width + height <= 10_000
                && bytes <= MAX_BYTES;
    }

    /**
     * The JPEG of {@code picture} ({@code width} × {@code height}, 0 if unknown) in {@code dir}, named like it.
     * Blocking; IOException when neither sips nor ffmpeg could make it.
     */
    public static Path jpeg(Path picture, int width, int height, Path dir) throws IOException, InterruptedException {
        return jpeg(picture, width, height, dir, Ffmpeg.WHEN_NEEDED);
    }

    /** {@code ffmpeg}: asked for only when sips can't (there's none, or it failed). */
    static Path jpeg(Path picture, int width, int height, Path dir, Ffmpeg.Source ffmpeg) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        String name = picture.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path out = dir.resolve((dot > 0 ? name.substring(0, dot) : name) + ".jpg");
        boolean shrink = width <= 0 || Math.max(width, height) > SIDE;
        Path made = PartFile.firstOf(out, TIMEOUT_SECONDS, PartFile.sipsThenFfmpeg(into -> sips(picture, into, shrink), ffmpeg,
                (found, into) -> ffmpeg(found, picture, into)));
        if (made == null) throw new IOException("Could not make a photo of " + name);
        return made;
    }

    /** sips's -Z also enlarges, so it's only given when the picture is bigger (or of unknown size). */
    static List<String> sips(Path in, Path out, boolean shrink) {
        List<String> c = new ArrayList<>(List.of(PartFile.SIPS.toString(), "-s", "format", "jpeg", "-s", "formatOptions", "85"));
        if (shrink) c.addAll(List.of("-Z", Integer.toString(SIDE)));
        c.addAll(List.of(in.toString(), "--out", out.toString()));
        return c;
    }

    static List<String> ffmpeg(Path ffmpeg, Path in, Path out) throws IOException {
        List<String> c = new java.util.ArrayList<>(List.of(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error"));
        c.addAll(Ffmpeg.input(in)); // the person's own, but it may have been downloaded from anywhere
        c.addAll(List.of("-vf", "scale='min(" + SIDE + ",iw)':'min(" + SIDE + ",ih)':force_original_aspect_ratio=decrease",
                "-q:v", "3", "-frames:v", "1", out.toString()));
        return c;
    }
}

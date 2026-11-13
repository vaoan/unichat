package app.unichat.support;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * A picture's width and height from its header, without decoding it: decoding a phone's 24-megapixel WebP just to
 * learn its size takes 600–900 MB (see video/BigPictures).
 */
public final class ImageHeaders {
    private ImageHeaders() {}

    /** Whether {@code file} is a WebM (Matroska's EBML header), whatever its name or label says. */
    public static boolean isWebm(Path file) throws java.io.IOException {
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && (head[0] & 0xFF) == 0x1A && head[1] == 0x45 && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
        }
    }

    /** An MP4's brands; an iPhone's HEIC or an AVIF photo has the same {@code ftyp} box with another (heic, mif1, avif). */
    private static final java.util.Set<String> MP4_BRANDS = java.util.Set.of("isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42",
            "avc1", "M4V ", "dash");

    /**
     * Whether {@code file} is a video ffmpeg turns into frames: a WebM (video stickers) or an MP4 (the ISO box {@code ftyp}
     * at byte 4 with a video's brand: the GIFs Telegram and WhatsApp send as video).
     */
    public static boolean isVideo(Path file) throws java.io.IOException {
        if (isWebm(file)) return true;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            byte[] head = in.readNBytes(12);
            return head.length == 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p'
                    && MP4_BRANDS.contains(new String(head, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
        }
    }

    /**
     * The picture's type told by its first bytes (GIF, WebP, PNG, JPEG), or null for anything else. Labels lie: a
     * bridge that converts a WebM sticker to a GIF can still call it video/webm.
     */
    public static String pictureMime(Path file) throws java.io.IOException {
        byte[] h = new byte[12];
        int n;
        try (var in = java.nio.file.Files.newInputStream(file)) {
            n = in.readNBytes(h, 0, 12);
        }
        if (n >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return "image/gif";
        if (n == 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B'
                && h[11] == 'P') return "image/webp";
        if (n >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "image/png";
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return "image/jpeg";
        return null;
    }

    /** {width, height}, or {0, 0} when no reader knows the format (HEIC) or the file can't be read. */
    public static int[] size(Path file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) return new int[]{0, 0};
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return new int[]{0, 0};
            ImageReader r = readers.next();
            try {
                r.setInput(in, true, true);
                return new int[]{r.getWidth(0), r.getHeight(0)};
            } finally {
                r.dispose();
            }
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }
}

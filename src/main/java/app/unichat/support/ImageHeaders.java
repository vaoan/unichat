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

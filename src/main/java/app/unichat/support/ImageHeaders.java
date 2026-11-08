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

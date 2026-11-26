package app.unichat.support;

import org.jspecify.annotations.Nullable;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The lightest file for a thumbnail the server made: an opaque PNG becomes a JPEG (quality 0.85) when that is smaller.
 * Anything else, or any failure, keeps the file as it came. Only ever called on a download made through
 * {@code /thumbnail}: what people sent is never converted.
 */
public final class LighterPictures {
    /** Pixels (width x height) above which a PNG is left alone before anything is decoded. */
    static final long PIXEL_LIMIT = 16_000_000L;
    private static final float QUALITY = 0.85f;
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private LighterPictures() {}

    /** The file to keep: {@code file} itself when left as it came, else the new {@code .jpg} beside it (the PNG is deleted). */
    public static Path lighter(Path file) {
        Path part = file.resolveSibling(base(file) + ".jpg.part");
        try {
            if (!"image/png".equals(ImageHeaders.pictureMime(file)) || !candidate(file)) return file;
            BufferedImage png = ImageIO.read(file.toFile());
            if (png == null) return file;
            BufferedImage rgb = new BufferedImage(png.getWidth(), png.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = rgb.createGraphics();
            try {
                g.drawImage(png, 0, 0, java.awt.Color.WHITE, null);
            } finally {
                g.dispose();
            }
            writeJpeg(rgb, part);
            if (Files.size(part) >= Files.size(file)) {
                Files.deleteIfExists(part);
                return file;
            }
            Path jpg = file.resolveSibling(base(file) + ".jpg");
            try {
                Files.move(part, jpg, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); // lesson-ok: atomic-move the encoder writes the .part itself
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, jpg, StandardCopyOption.REPLACE_EXISTING); // lesson-ok: atomic-move same as above
            }
            Files.deleteIfExists(file);
            return jpg;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // a leftover .part is cleaned by the cache trim
            }
            return file;
        }
    }

    /** Within the pixel limit and either opaque by its header or by every pixel's alpha. */
    private static boolean candidate(Path png) throws IOException {
        long[] dims = new long[2];
        Boolean byHeader = header(png, dims);
        if (byHeader == null || dims[0] * dims[1] > PIXEL_LIMIT) return false;
        if (byHeader) return true;
        BufferedImage img = ImageIO.read(png.toFile());
        return img != null && allOpaque(img);
    }

    private static boolean allOpaque(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) return true;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 255) return false;
            }
        }
        return true;
    }

    /** Opaque for sure by the header (colour type 0 or 2, no tRNS before IDAT), or not for sure; null when unreadable. */
    static @Nullable Boolean header(Path png, long[] dims) throws IOException {
        try (InputStream raw = Files.newInputStream(png); DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw))) {
            byte[] sig = in.readNBytes(8);
            if (!java.util.Arrays.equals(sig, SIGNATURE)) return null;
            if (in.readInt() != 13 || in.readInt() != 0x49484452) return null; // IHDR
            dims[0] = in.readInt() & 0xFFFFFFFFL;
            dims[1] = in.readInt() & 0xFFFFFFFFL;
            in.readByte(); // bit depth
            int colour = in.readUnsignedByte();
            in.skipNBytes(3 + 4); // compression, filter, interlace, CRC
            boolean opaque = colour == 0 || colour == 2;
            while (true) {
                long len = in.readInt() & 0xFFFFFFFFL;
                int type = in.readInt();
                if (type == 0x49444154) return opaque; // IDAT
                if (type == 0x74524E53) opaque = false; // tRNS
                in.skipNBytes(len + 4);
            }
        } catch (java.io.EOFException e) {
            return null;
        }
    }

    /** Whether the PNG is opaque by its header alone (no pixel scan); false when it may have transparency or can't be read. */
    static boolean opaque(Path png) throws IOException {
        return Boolean.TRUE.equals(header(png, new long[2]));
    }

    private static void writeJpeg(BufferedImage img, Path out) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream os = ImageIO.createImageOutputStream(out.toFile())) {
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(QUALITY);
            writer.setOutput(os);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
    }

    private static String base(Path file) {
        String n = PathParts.name(file);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}

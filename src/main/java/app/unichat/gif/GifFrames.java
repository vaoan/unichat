package app.unichat.gif;

import app.unichat.support.I18n;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The frames of an animated GIF as whole pictures: GIF frames are patches drawn over the previous one (with a
 * position and a "disposal" rule), so each is composited onto the canvas before it's handed out.
 */
public final class GifFrames {
    /** {@code delayMs}: how long the frame stays on screen. */
    public record Frame(BufferedImage image, int delayMs) {}

    /** Browsers show frames that ask for 0–10 ms for 100 ms; so do we. */
    private static final int DEFAULT_DELAY_MS = 100;

    private GifFrames() {}

    public static List<Frame> read(Path gif) throws IOException {
        List<Frame> out = new ArrayList<>();
        read(gif, out::add);
        return out;
    }

    /** Frame count without decoding any frame. */
    public static int count(Path gif) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(gif.toFile())) {
            if (in == null) throw new IOException(I18n.t("gif.unreadable"));
            ImageReader r = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                r.setInput(in, false);
                return r.getNumImages(true);
            } finally {
                r.dispose();
            }
        }
    }

    /**
     * Hands each composited frame to {@code each} as soon as it's ready (a private copy), so a caller that scales
     * them down never holds every full-size frame at once.
     */
    public static void read(Path gif, java.util.function.Consumer<Frame> each) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(gif.toFile())) {
            if (in == null) throw new IOException(I18n.t("gif.unreadable"));
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new IOException(I18n.t("gif.noReader"));
            ImageReader r = readers.next();
            try {
                r.setInput(in, false);
                int count = r.getNumImages(true);
                int w = 0, h = 0;
                Node screen = child(r.getStreamMetadata() == null ? null : tree(r.getStreamMetadata()), "LogicalScreenDescriptor");
                if (screen != null) {
                    w = intAttr(screen, "logicalScreenWidth", 0);
                    h = intAttr(screen, "logicalScreenHeight", 0);
                }
                BufferedImage first = r.read(0);
                if (w <= 0 || h <= 0) {
                    w = first.getWidth();
                    h = first.getHeight();
                }
                BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                for (int i = 0; i < count; i++) {
                    BufferedImage patch = i == 0 ? first : r.read(i);
                    Node meta = tree(r.getImageMetadata(i));
                    Node desc = child(meta, "ImageDescriptor"), gce = child(meta, "GraphicControlExtension");
                    int x = desc == null ? 0 : intAttr(desc, "imageLeftPosition", 0);
                    int y = desc == null ? 0 : intAttr(desc, "imageTopPosition", 0);
                    int delay = gce == null ? 0 : intAttr(gce, "delayTime", 0) * 10;
                    String disposal = gce == null ? "none" : attr(gce, "disposalMethod", "none");

                    BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
                    Graphics2D g = canvas.createGraphics();
                    g.drawImage(patch, x, y, null);
                    g.dispose();
                    each.accept(new Frame(copy(canvas), delay <= 10 ? DEFAULT_DELAY_MS : delay));

                    if ("restoreToBackgroundColor".equals(disposal)) {
                        Graphics2D c = canvas.createGraphics();
                        c.setComposite(java.awt.AlphaComposite.Clear);
                        c.fillRect(x, y, patch.getWidth(), patch.getHeight());
                        c.dispose();
                    } else if (before != null) {
                        canvas = before;
                    }
                }
            } finally {
                r.dispose();
            }
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = c.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return c;
    }

    private static Node tree(IIOMetadata m) {
        return m.getAsTree(m.getNativeMetadataFormatName());
    }

    private static Node child(Node parent, String name) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (name.equals(n.getNodeName())) return n;
        }
        return null;
    }

    private static String attr(Node n, String name, String fallback) {
        Node a = n.getAttributes() == null ? null : n.getAttributes().getNamedItem(name);
        return a == null ? fallback : a.getNodeValue();
    }

    private static int intAttr(Node n, String name, int fallback) {
        try {
            return Integer.parseInt(attr(n, name, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

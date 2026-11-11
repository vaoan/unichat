package app.unichat;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/** Pictures for tests, written as real files: a still PNG or JPEG, and an animated GIF. */
public final class TestPictures {
    private TestPictures() {}

    /** Draws frame {@code frame} of a picture. */
    public interface Painter {
        void paint(Graphics2D g, int frame);
    }

    /** A {@code w}×{@code h} gradient as {@code format} ("png", "jpg") at {@code p}. */
    public static Path still(Path p, String format, int w, int h) throws IOException {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, w, h, Color.BLUE));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ImageIO.write(b, format, p.toFile());
        return p;
    }

    /** A looping GIF of {@code frames} frames, 40 ms each, drawn by {@code painter}. */
    public static Path gif(Path p, int w, int h, int frames, Painter painter) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(p.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int f = 0; f < frames; f++) {
                BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED);
                Graphics2D g = b.createGraphics();
                painter.paint(g, f);
                g.dispose();
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(b), param);
                String format = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                ((IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0)).setAttribute("delayTime", "4");
                meta.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(b, null, meta), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return p;
    }

    /** A GIF of moving squares. */
    public static Path gif(Path p, int w, int h, int frames) throws IOException {
        return gif(p, w, h, frames, (g, f) -> {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.MAGENTA);
            g.fillRect((f * 7) % Math.max(1, w - 10), (f * 5) % Math.max(1, h - 10), 10, 10);
        });
    }
}

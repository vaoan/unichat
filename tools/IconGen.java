import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** Renders the Unichat icons: java tools/IconGen.java src/main/resources/app/unichat/icons packaging */
public class IconGen {
    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        dir.mkdirs();
        for (int s : new int[]{16, 32, 64, 128, 256, 512, 1024}) {
            ImageIO.write(render(s), "png", new File(dir, "app-" + s + ".png"));
        }
        ImageIO.write(render(32), "png", new File(dir, "tray.png"));
        File icoDir = new File(args.length > 1 ? args[1] : dir.getPath());
        writeIco(new File(icoDir, "unichat.ico"), new int[]{16, 24, 32, 48, 64, 128, 256});
    }

    /** Windows .ico with PNG-compressed entries (supported since Vista). */
    static void writeIco(File out, int[] sizes) throws Exception {
        java.util.List<byte[]> pngs = new java.util.ArrayList<>();
        for (int s : sizes) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            ImageIO.write(render(s), "png", b);
            pngs.add(b.toByteArray());
        }
        java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(6 + 16 * sizes.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            head.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            head.putShort((short) 1).putShort((short) 32).putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(out)) {
            f.write(head.array());
            for (byte[] p : pngs) f.write(p);
        }
    }

    static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double s = size;
        double pad = s * 0.06;
        g.setPaint(new GradientPaint(0, 0, new Color(0x7C6CF2), (float) s, (float) s, new Color(0x4A4AD0)));
        g.fill(new RoundRectangle2D.Double(pad, pad, s - 2 * pad, s - 2 * pad, s * 0.42, s * 0.42));
        // Chat bubble
        double bx = s * 0.22, by = s * 0.24, bw = s * 0.56, bh = s * 0.42;
        GeneralPath bubble = new GeneralPath();
        bubble.append(new RoundRectangle2D.Double(bx, by, bw, bh, bh * 0.7, bh * 0.7), false);
        bubble.moveTo(bx + bw * 0.22, by + bh * 0.9);
        bubble.lineTo(bx + bw * 0.14, by + bh * 1.35);
        bubble.lineTo(bx + bw * 0.46, by + bh * 0.95);
        bubble.closePath();
        g.setColor(Color.WHITE);
        g.fill(bubble);
        // Three dots in network colors
        Color[] dots = {new Color(0x25D366), new Color(0x2AABEE), new Color(0xE1306C)};
        double r = Math.max(1.5, s * 0.055);
        for (int i = 0; i < 3; i++) {
            double cx = bx + bw * (0.28 + i * 0.22), cy = by + bh * 0.5;
            g.setColor(dots[i]);
            g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        }
        if (size >= 64) {
            g.setColor(new Color(255, 255, 255, 40));
            g.setStroke(new BasicStroke((float) (s * 0.01)));
            g.draw(new RoundRectangle2D.Double(pad, pad, s - 2 * pad, s - 2 * pad, s * 0.42, s * 0.42));
        }
        g.dispose();
        return img;
    }
}

package app.unichat.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LighterPicturesTest {
    @TempDir
    Path dir;

    private Path png(String name, int type, int w, int h, boolean noisy, int alphaAt00) throws IOException {
        BufferedImage img = new BufferedImage(w, h, type);
        Random r = new Random(7);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = noisy ? (r.nextInt(256) << 16 | (x * 255 / w) << 8 | (y * 255 / h)) : 0x336699;
                img.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        if (img.getColorModel().hasAlpha()) img.setRGB(0, 0, alphaAt00 << 24 | 0x336699);
        Path p = dir.resolve(name);
        assertTrue(ImageIO.write(img, "png", p.toFile()));
        return p;
    }

    @Test
    void anOpaquePngBecomesASmallerJpeg() throws Exception {
        Path png = png("a.png", BufferedImage.TYPE_INT_RGB, 320, 320, true, 255);
        long before = Files.size(png);
        Path out = LighterPictures.lighter(png);
        assertEquals("a.jpg", out.getFileName().toString());
        assertTrue(Files.size(out) < before);
        assertFalse(Files.exists(png));
        assertEquals("image/jpeg", ImageHeaders.pictureMime(out));
        assertArrayEquals(new int[]{320, 320}, ImageHeaders.size(out));
    }

    @Test
    void anRgbaPngWhoseAlphaIsAllOpaqueBecomesAJpeg() throws Exception {
        Path png = png("b.png", BufferedImage.TYPE_INT_ARGB, 320, 320, true, 255);
        assertFalse(LighterPictures.opaque(png), "alpha channel: needs the pixel scan");
        assertEquals("b.jpg", LighterPictures.lighter(png).getFileName().toString());
    }

    @Test
    void aTransparentPngStaysAsItCame() throws Exception {
        Path png = png("c.png", BufferedImage.TYPE_INT_ARGB, 320, 320, true, 0);
        byte[] bytes = Files.readAllBytes(png);
        assertEquals(png, LighterPictures.lighter(png));
        assertArrayEquals(bytes, Files.readAllBytes(png));
    }

    @Test
    void aPalettePngWithTransparencyStaysAsItCame() throws Exception {
        byte[] pal = {0, 0, 0, (byte) 255, (byte) 255, (byte) 255};
        var cm = new java.awt.image.IndexColorModel(1, 2, pal, 0, false, 0); // index 0 transparent: written as tRNS
        BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_BYTE_BINARY, cm);
        Random r = new Random(3);
        for (int y = 0; y < 300; y++) for (int x = 0; x < 300; x++) img.getRaster().setSample(x, y, 0, r.nextInt(2));
        Path png = dir.resolve("d.png");
        assertTrue(ImageIO.write(img, "png", png.toFile()));
        assertTrue(Files.readString(png, java.nio.charset.StandardCharsets.ISO_8859_1).contains("tRNS"), "the test picture has tRNS");
        assertEquals(png, LighterPictures.lighter(png));
        assertTrue(Files.exists(png));
    }

    @Test
    void aPngWhoseJpegWouldBeBiggerStaysAsItCame() throws Exception {
        Path png = png("e.png", BufferedImage.TYPE_INT_RGB, 16, 16, false, 255);
        assertEquals(png, LighterPictures.lighter(png));
        assertTrue(Files.exists(png));
        try (var left = Files.list(dir)) {
            assertEquals(1, left.count(), "no .part left");
        }
    }

    @Test
    void aJpegWebpOrGifStaysAsItCame() throws Exception {
        Path jpg = dir.resolve("f.jpg");
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "jpg", jpg.toFile());
        Path gif = dir.resolve("g.gif");
        Files.writeString(gif, "GIF89a-not-really");
        Path webp = dir.resolve("h.webp");
        Files.write(webp, "RIFF....WEBPVP8 ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        for (Path p : new Path[]{jpg, gif, webp}) assertEquals(p, LighterPictures.lighter(p));
    }

    @Test
    void aBrokenPngStaysAsItCame() throws Exception {
        Path p = dir.resolve("i.png");
        Files.write(p, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4, 5, 6, 7, 8, 9});
        assertEquals(p, LighterPictures.lighter(p));
        assertTrue(Files.exists(p));
    }

    @Test
    void aPngPastThePixelLimitIsNotDecoded() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(ihdr);
        h.writeBytes("IHDR");
        h.writeInt(20000);
        h.writeInt(20000);
        h.write(new byte[]{8, 2, 0, 0, 0});
        byte[] data = ihdr.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(data);
        o.writeInt(13);
        o.write(data);
        o.writeInt((int) crc.getValue());
        o.writeInt(0);
        o.writeBytes("IDAT");
        o.writeInt(0);
        Path p = dir.resolve("j.png");
        Files.write(p, b.toByteArray());
        assertTrue(LighterPictures.opaque(p), "by its header it is opaque");
        assertEquals(p, LighterPictures.lighter(p));
    }
}

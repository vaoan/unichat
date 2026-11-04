package app.unichat.matrix;

import app.unichat.gif.GifFrames;
import app.unichat.model.Network;
import app.unichat.service.StickerMode;
import app.unichat.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** WhatsApp → Redes → Stickers animados: GIF or Video sends the animated sticker as an mp4 instead. */
class StickerVideoTest {
    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        Settings.get().set(StickerMode.key(Network.WHATSAPP), null);
    }

    /** A two-frame animated GIF (red, then blue), 0.5 s per frame. */
    static Path animatedGif(Path dir) throws Exception {
        Path f = dir.resolve("anim.gif");
        ImageWriter w = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(f.toFile())) {
            w.setOutput(out);
            w.prepareWriteSequence(null);
            for (int rgb : new int[]{0xff0000, 0x0000ff}) {
                BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
                for (int x = 0; x < 32; x++) for (int y = 0; y < 32; y++) img.setRGB(x, y, rgb);
                IIOMetadata meta = w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), null);
                String fmt = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);
                IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", "50");
                gce.setAttribute("transparentColorIndex", "0");
                root.appendChild(gce);
                meta.setFromTree(fmt, root);
                w.writeToSequence(new IIOImage(img, null, meta), null);
            }
            w.endWriteSequence();
        }
        return f;
    }

    @Test
    void gifFramesComeWithTheirDelays() throws Exception {
        List<GifFrames.Frame> frames = GifFrames.read(animatedGif(dir));
        assertEquals(2, frames.size());
        assertEquals(500, frames.get(0).delayMs());
        assertEquals(32, frames.get(1).image().getWidth());
        assertEquals(0x0000ff, frames.get(1).image().getRGB(5, 5) & 0xffffff);
    }

    @Test
    void anAnimatedGifBecomesAnMp4() throws Exception {
        StickerConverter.Result r = StickerConverter.toMp4(animatedGif(dir), dir);
        assertEquals("video/mp4", r.mime());
        byte[] b = Files.readAllBytes(r.file());
        assertEquals("ftyp", new String(b, 4, 4));
    }

    @Test
    void theChoiceIsPerNetworkAndDefaultsToSticker() {
        assertEquals(StickerMode.STICKER, StickerMode.of(Network.WHATSAPP));
        Settings.get().set(StickerMode.key(Network.WHATSAPP), "GIF");
        assertEquals(StickerMode.GIF, StickerMode.of(Network.WHATSAPP));
        assertEquals(StickerMode.STICKER, StickerMode.of(Network.TELEGRAM));
        Settings.get().set(StickerMode.key(Network.WHATSAPP), "nonsense");
        assertEquals(StickerMode.STICKER, StickerMode.of(Network.WHATSAPP));
    }
}

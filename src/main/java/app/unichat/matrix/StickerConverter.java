package app.unichat.matrix;

import app.unichat.gif.GifFrames;
import app.unichat.model.RoomFeatures;
import app.unichat.support.BundledTools;
import app.unichat.support.I18n;
import app.unichat.support.Processes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Stickers in a format the chat refuses (Telegram's animated stickers arrive as GIF; WhatsApp only takes WebP/PNG):
 * which format to send instead, and the conversion. GIF → animated WebP uses libwebp's {@code gif2webp}, shipped
 * inside the app; without it the first frame goes out as a still PNG.
 */
public final class StickerConverter {
    static final String WEBP = "image/webp", PNG = "image/png";

    record Result(Path file, String mime) {}

    private static final int FPS = 25;
    /** Short stickers are looped up to this long, so a video isn't over before it's seen. */
    private static final int MIN_VIDEO_MS = 3000;

    /**
     * An animated GIF as an H.264 mp4 (JCodec, pure Java: no ffmpeg on Windows). Transparent areas become white
     * and odd sizes are padded to even, as H.264 needs. Written as {@code sticker.mp4} in a new folder under {@code dir}.
     */
    static Result toMp4(Path gif, Path dir) throws IOException {
        java.util.List<GifFrames.Frame> frames = GifFrames.read(gif);
        if (frames.isEmpty()) throw new IOException(I18n.t("sticker.noFrames"));
        int w = frames.get(0).image().getWidth(), h = frames.get(0).image().getHeight();
        int ew = w + (w & 1), eh = h + (h & 1);
        Files.createDirectories(dir);
        Path out = Files.createTempDirectory(dir, "sticker").resolve("sticker.mp4");
        org.jcodec.api.awt.AWTSequenceEncoder enc = org.jcodec.api.awt.AWTSequenceEncoder.createSequenceEncoder(out.toFile(), FPS);
        int total = 0;
        do {
            for (GifFrames.Frame f : frames) {
                BufferedImage flat = new BufferedImage(ew, eh, BufferedImage.TYPE_3BYTE_BGR);
                java.awt.Graphics2D g = flat.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, ew, eh);
                g.drawImage(f.image(), 0, 0, null);
                g.dispose();
                int repeats = Math.max(1, Math.round(f.delayMs() * FPS / 1000f));
                for (int i = 0; i < repeats; i++) enc.encodeImage(flat);
                total += repeats * 1000 / FPS;
            }
        } while (total < MIN_VIDEO_MS);
        enc.finish();
        return new Result(out, "video/mp4");
    }

    private StickerConverter() {}

    /** The format to convert a {@code mime} sticker to for a chat with {@code f}; null = send it as it is. */
    static String target(String mime, RoomFeatures f) {
        if (f.isUnknown() || !f.accepts(RoomFeatures.STICKER) || f.acceptsMime(RoomFeatures.STICKER, mime)) return null;
        if (f.acceptsMime(RoomFeatures.STICKER, WEBP)) return WEBP;
        if (f.acceptsMime(RoomFeatures.STICKER, PNG)) return PNG;
        return null;
    }

    /**
     * {@code gif2webp}: {@code -Dunichat.gif2webp} (development), the copy inside the app (app/server/), or one on
     * the PATH; null when there's none.
     */
    static Path gif2webp() {
        return BundledTools.find("unichat.gif2webp", null, "gif2webp");
    }

    static Result convert(Path in, String target, Path dir) throws IOException {
        return convert(in, target, dir, gif2webp());
    }

    /** Converts {@code in} into {@code dir}; a WebP that can't be made (no tool, tool failed) becomes a PNG. */
    static Result convert(Path in, String target, Path dir, Path tool) throws IOException {
        Files.createDirectories(dir);
        if (WEBP.equals(target) && tool != null) {
            Path out = Files.createTempFile(dir, "sticker", ".webp");
            try {
                Processes.Result r = Processes.quiet(List.of(tool.toString(), "-mixed", "-q", "80", "-m", "4", in.toString(), "-o", out.toString()), null, 60);
                if (r.ok() && Files.size(out) > 0) return new Result(out, WEBP);
                System.err.println("gif2webp failed (exit " + (r.finished() ? r.exit() : "timeout") + "); sending a still sticker");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(I18n.t("sticker.interrupted"), e);
            }
            Files.deleteIfExists(out);
        }
        BufferedImage first = ImageIO.read(in.toFile());
        if (first == null) throw new IOException(I18n.t("sticker.unreadable"));
        Path out = Files.createTempFile(dir, "sticker", ".png");
        ImageIO.write(first, "png", out.toFile());
        return new Result(out, PNG);
    }
}

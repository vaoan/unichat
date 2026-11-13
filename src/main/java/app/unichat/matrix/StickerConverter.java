package app.unichat.matrix;

import app.unichat.gif.GifFrames;
import app.unichat.model.Network;
import app.unichat.model.RoomFeatures;
import app.unichat.support.BundledTools;
import app.unichat.support.ImageHeaders;
import app.unichat.support.I18n;
import app.unichat.support.Processes;
import app.unichat.video.WebmStickers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A sticker made the format a chat gets it as ({@link NetworkFormats} decides which): WebM → animated WebP (img2webp,
 * transparency kept), animated GIF → WebM video sticker, GIF → animated WebP (gif2webp), anything → a still PNG. A way
 * whose tool isn't there or fails gives the sticker as it is, or a still PNG. It knows formats, not networks.
 */
public final class StickerConverter {
    static final String WEBP = NetworkFormats.WEBP, PNG = NetworkFormats.PNG, WEBM = NetworkFormats.WEBM, GIF = NetworkFormats.GIF;

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

    /**
     * What to upload instead of the {@code mime} sticker {@code src}, null for the file as it is: the format
     * {@code network} gets it as ({@link NetworkFormats#sticker}; null network: what the chat's room_features take),
     * {@code own} when it's that network's own sticker.
     */
    static Result forChat(Path src, String mime, Network network, boolean own, RoomFeatures f, Path dir) throws IOException {
        String real = ImageHeaders.pictureMime(src);
        if (real != null && !real.equals(mime)) { // labelled one thing, really a picture: it goes as what it is
            Result as = forChat(src, real, network, own, f, dir);
            return as != null ? as : copy(src, real, dir);
        }
        boolean moves = animated(src, mime);
        String want = network == null ? NetworkFormats.byFeatures(mime, f) : NetworkFormats.sticker(network, mime, moves, own, f);
        if (want == null) return null;
        Result r = to(src, mime, want, dir);
        boolean unmade = r != null && !want.equals(r.mime());
        if (unmade && NetworkFormats.asIsIfUnmade(network, mime, moves)) {
            Files.deleteIfExists(r.file());
            return null;
        }
        return r;
    }

    /** Whether the {@code mime} sticker {@code src} moves: a video sticker always, a GIF with more than one frame. */
    static boolean animated(Path src, String mime) throws IOException {
        return WEBM.equals(mime) || NetworkFormats.LOTTIE.equals(mime) || GIF.equals(mime) && GifFrames.count(src) > 1;
    }

    /** {@code src} ({@code from}) made {@code want}, each way falling back to the next best; null: it goes as it is. */
    static Result to(Path src, String from, String want, Path dir) throws IOException {
        // A video sticker only becomes an animated WebP; without img2webp it goes as the WebM it is.
        if (WEBM.equals(from)) return WEBP.equals(want) ? fromVideo(src, dir, ".webp", WEBP, WebmStickers::toWebp, "WebP") : null;
        if (WEBM.equals(want)) {
            Result webm = fromVideo(src, dir, ".webm", WEBM, WebmStickers::fromGif, "WebM");
            if (webm != null) return webm;
            return convert(src, PNG, dir, null); // no ffmpeg: a still sticker
        }
        return convert(src, want, dir);
    }

    /** A conversion run by ffmpeg ({@code video/WebmStickers}). */
    @FunctionalInterface
    interface Making {
        Path make(Path in, Path out, java.util.function.DoubleConsumer installProgress) throws IOException, InterruptedException;
    }

    /** {@code in} made {@code mime} into a new file of {@code dir}; null when it can't be (no tool, or it failed: logged). */
    private static Result fromVideo(Path in, Path dir, String extension, String mime, Making making, String what) throws IOException {
        Files.createDirectories(dir);
        Path out = Files.createTempFile(dir, "sticker", extension);
        try {
            return new Result(making.make(in, out, p -> { }), mime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(out);
            throw new IOException(I18n.t("sticker.interrupted"), e);
        } catch (IOException e) {
            Files.deleteIfExists(out);
            System.err.println("Sticker to " + what + " failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return null;
        }
    }

    /** A copy of {@code src} to upload as {@code mime} (the caller deletes what it uploads; {@code src} is the cache's). */
    private static Result copy(Path src, String mime, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path out = Files.createTempFile(dir, "sticker", extension(mime));
        Files.copy(src, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new Result(out, mime);
    }

    /** The file name's extension for a converted sticker. */
    static String extension(String mime) {
        return switch (mime) {
            case WEBP -> ".webp";
            case WEBM -> ".webm";
            case GIF -> ".gif";
            case "image/jpeg" -> ".jpg";
            default -> ".png";
        };
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

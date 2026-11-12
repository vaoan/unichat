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
 * Stickers in a format the chat refuses (Telegram's animated stickers arrive as GIF; WhatsApp only takes WebP/PNG):
 * which format to send instead, and the conversion. GIF → animated WebP uses libwebp's {@code gif2webp}, shipped
 * inside the app; without it the first frame goes out as a still PNG.
 */
public final class StickerConverter {
    static final String WEBP = "image/webp", PNG = "image/png", WEBM = "video/webm", GIF = "image/gif";
    /**
     * What Telegram's bridge sends on as a sticker without its ffmpeg: PNG and JPEG it encodes to WebP itself, WebM and
     * Lottie go as they are. Anything else, WebP included, goes through ffmpeg, which fails without a WebP encoder
     * (Homebrew's has none), whatever the chat's room_features say.
     */
    private static final List<String> TELEGRAM_AS_IS = List.of(PNG, "image/jpeg", WEBM, "video/lottie+json");

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

    /** Whether a {@code mime} sticker, not from Telegram's own packs, has to be converted before Telegram's bridge gets it. */
    static boolean forTelegram(Network network, String mime) {
        return network == Network.TELEGRAM && !TELEGRAM_AS_IS.contains(mime);
    }

    /**
     * {@code in} as Telegram's bridge takes it: an animated GIF as a video sticker (WebM), the rest, or a GIF when
     * there's no ffmpeg to convert with, as a still PNG.
     */
    static Result toTelegram(Path in, String mime, Path dir) throws IOException {
        if (GIF.equals(mime) && GifFrames.count(in) > 1) {
            Files.createDirectories(dir);
            Path out = Files.createTempFile(dir, "sticker", ".webm");
            try {
                return new Result(WebmStickers.fromGif(in, out, p -> { }), WEBM);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Files.deleteIfExists(out);
                throw new IOException(I18n.t("sticker.interrupted"), e);
            } catch (IOException e) {
                Files.deleteIfExists(out);
                System.err.println("Sticker to WebM failed (" + e.getClass().getSimpleName() + "); sending a still sticker");
            }
        }
        return convert(in, PNG, dir, null);
    }

        /**
     * Whether a GIF that came as a video (an MP4 marked {@code fi.mau.gif}) must go to {@code network} as a real GIF:
     * Telegram's and WhatsApp's bridges send the mark on (both show such an MP4 as a GIF); others, like Discord's, show
     * a video with a play button.
     */
    static boolean gifAsGif(Network network) {
        return network != Network.TELEGRAM && network != Network.WHATSAPP;
    }

/**
     * Whether a {@code mime} sticker goes to {@code network} as a GIF: a WebM (Telegram's video stickers) is a video to
     * press play on everywhere but Telegram, Discord included, and a GIF plays by itself.
     */
    static boolean asGif(Network network, String mime) {
        return network != Network.TELEGRAM && WEBM.equals(mime);
    }

    /**
     * What to upload instead of the {@code mime} sticker {@code src}, null for the file as it is: Telegram's format
     * ({@code telegram}), a WebM as a GIF ({@code gif}), then whatever the chat's {@code f} asks for (a GIF as WebP on
     * WhatsApp). A WebM that can't be made a GIF (no ffmpeg) goes on as it is.
     */
    static Result forChat(Path src, String mime, boolean telegram, boolean gif, RoomFeatures f, Path dir) throws IOException {
        String real = ImageHeaders.pictureMime(src);
        if (real != null && !real.equals(mime)) { // labelled one thing, really a picture: it goes as what it is
            Result as = forChat(src, real, telegram && forTelegram(Network.TELEGRAM, real), false, f, dir);
            return as != null ? as : copy(src, real, dir);
        }
        if (telegram) return toTelegram(src, mime, dir);
        Result r = gif ? webmToGif(src, dir) : null;
        String target = target(r == null ? mime : r.mime(), f);
        if (target == null) return r;
        Result c = convert(r == null ? src : r.file(), target, dir);
        if (r != null) Files.deleteIfExists(r.file());
        return c;
    }

    /** A copy of {@code src} to upload as {@code mime} (the caller deletes what it uploads; {@code src} is the cache's). */
    private static Result copy(Path src, String mime, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path out = Files.createTempFile(dir, "sticker", extension(mime));
        Files.copy(src, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new Result(out, mime);
    }

    /** {@code in}, a WebM, as an animated GIF; null when it can't be (no ffmpeg, or it failed). */
    static Result webmToGif(Path in, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path out = Files.createTempFile(dir, "sticker", ".gif");
        try {
            return new Result(WebmStickers.toGif(in, out, p -> { }), GIF);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(out);
            throw new IOException(I18n.t("sticker.interrupted"), e);
        } catch (IOException e) {
            Files.deleteIfExists(out);
            System.err.println("Sticker to GIF failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "); sending the WebM");
            return null;
        }
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

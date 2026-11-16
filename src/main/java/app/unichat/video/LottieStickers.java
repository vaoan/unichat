package app.unichat.video;

import app.unichat.support.BundledTools;
import app.unichat.support.I18n;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.zip.GZIPInputStream;

/**
 * Lottie stickers (Telegram's animated ones: a .tgs is gzipped Lottie JSON; Discord's and WhatsApp's own come as the
 * JSON), which neither JavaFX nor any other network reads: made an animated WebP with their transparency, drawn by
 * LottieConverter (Samsung's rlottie, {@code native/build-lottie.sh}) frame by frame and put together by img2webp.
 */
public final class LottieStickers {
    /** Telegram's are 512 px, at most 3 s and 60 frames a second; 30 is plenty to look at. */
    private static final int SIDE = 512, FPS = 30, QUALITY = 80;
    /**
     * Longer isn't a sticker (Telegram's last 3 s at most): each frame is written whole to disk (1 MB at 512 px) before
     * the WebP is made, so this caps that at 300 MB.
     */
    private static final double MAX_SECONDS = 10;
    /** A .tgs may be 64 KB; its JSON a few hundred. More is a file made to fill the memory as it unpacks. */
    static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final long TIMEOUT_SECONDS = 60;
    /** Telegram's run at 30 or 60 frames a second; anything far from that isn't a sticker. */
    private static final double MIN_RATE = 1, MAX_RATE = 120;
    private static final ObjectMapper JSON = new ObjectMapper();

    private LottieStickers() {}

    /** {@code -Dunichat.lottieconverter} (development), the copy inside the app, or one on the PATH; null when there's none. */
    public static Path tool() {
        return BundledTools.find("unichat.lottieconverter", null, "lottieconverter");
    }

    /** Whether {@code file} is a Lottie animation, gzipped (.tgs) or not, whatever it's labelled. */
    public static boolean isLottie(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(2);
        }
        if (head.length < 2) return false;
        if ((head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) return true; // gzip: nothing else a sticker comes as
        if (head[0] != '{') return false;
        try {
            JsonNode n = JSON.readTree(json(file));
            return n.has("layers") && n.has("fr") && n.has("op");
        } catch (IOException e) {
            return false;
        }
    }

    /** The animation's JSON, unpacked when it came gzipped; refused past {@link #MAX_JSON_BYTES}. */
    static byte[] json(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = raw.markSupported() ? raw : new java.io.BufferedInputStream(raw)) {
            in.mark(2);
            boolean gzip = in.read() == 0x1F && in.read() == 0x8B;
            in.reset();
            InputStream body = gzip ? new GZIPInputStream(in) : in;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (body.transferTo(new Capped(out)) > MAX_JSON_BYTES) throw new IOException(I18n.t("video.stickerFailed"));
            return out.toByteArray();
        }
    }

    /** Counts what passes and stops past the cap, so a bomb never unpacks whole. */
    private static final class Capped extends OutputStream {
        private final OutputStream to;
        private long written;

        Capped(OutputStream to) { this.to = to; }

        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            written += len;
            if (written > MAX_JSON_BYTES) throw new IOException(I18n.t("video.stickerFailed"));
            to.write(b, off, len);
        }
    }

    /**
     * How long it lasts, in seconds, from what it says of itself (frames a second, first and last frame): what the tool
     * would write that many frames from. An IOException when that isn't a sticker's.
     */
    static double check(byte[] json) throws IOException {
        JsonNode n = JSON.readTree(json);
        double fr = n.path("fr").asDouble(0), ip = n.path("ip").asDouble(0), op = n.path("op").asDouble(0);
        // Counted as rlottie counts them (first and last frame rounded, both drawn), and as LottieConverter writes
        // them, FPS a second: by the file's own seconds, "fr":0.000001 and "op":0.00001 were 10 s and 30 million frames.
        long own = Math.round(op) - Math.round(ip) + 1;
        double frames = own * (double) FPS / fr;
        if (!(fr >= MIN_RATE && fr <= MAX_RATE && op > ip && frames <= MAX_SECONDS * FPS)) {
            throw new IOException(I18n.t("video.stickerFailed"));
        }
        // A picture inside is decoded whole whatever size it says (a 2 KB .tgs took 637 MB): stickers are drawn.
        for (JsonNode asset : n.path("assets")) {
            if (asset.has("p")) throw new IOException(I18n.t("video.stickerFailed"));
        }
        return (op - ip) / fr;
    }

    /**
     * {@code lottie} as an animated WebP at {@code out}, its transparency kept. Blocking; an IOException without
     * LottieConverter or img2webp, or when it can't be made. {@code installProgress} is unused: nothing is downloaded.
     */
    public static Path toWebp(Path lottie, Path out, DoubleConsumer installProgress) throws IOException, InterruptedException {
        Path tool = tool(), img2webp = AnimatedWebp.img2webp();
        if (tool == null) throw new IOException("lottieconverter isn't there");
        if (img2webp == null) throw new IOException("img2webp isn't there");
        byte[] json = json(lottie);
        check(json);
        AnimatedWebp.fromFrames(img2webp, out, QUALITY, frames -> {
            // Only JSON that passed the checks reaches the tool, never the file as it came. Names relative to its folder:
            // the tool uses the output's name as a printf format, and a "%" in a folder's name would break it.
            Files.write(frames.resolve("in.json"), json);
            AnimatedWebp.run(new ProcessBuilder(tool.toString(), "in.json", "f_", "pngs", SIDE + "x" + SIDE, Integer.toString(FPS))
                    .directory(frames.toFile()), "lottieconverter", TIMEOUT_SECONDS);
            List<Path> pngs;
            try (var list = Files.list(frames)) {
                pngs = list.filter(f -> f.getFileName().toString().endsWith(".png")).sorted().toList();
            }
            for (Path png : pngs.subList(0, Math.min(pngs.size(), AnimatedWebp.MAX_FRAMES))) {
                writePam(ImageIO.read(png.toFile()), frames.resolve(png.getFileName().toString().replace(".png", ".pam")));
                Files.delete(png);
            }
            return count -> 1000 / FPS;
        });
        return out;
    }

    /**
     * One conversion per file at a time, and a failed one not tried again: a sticker is shown twice at once (its still
     * picture and its animation), and both made the same WebP in the same frames folder, each deleting the other's
     * frames; one that can't be made was made again at every look, holding a decoder thread each time.
     */
    static final class Once {
        /** Each file's conversion, going or done (made or failed), the newest 500: a few hundred paths. */
        private final java.util.Map<Path, java.util.concurrent.CompletableFuture<Path>> made = java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<>(16, 0.75f, true) {
                    @Override protected boolean removeEldestEntry(java.util.Map.Entry<Path, java.util.concurrent.CompletableFuture<Path>> e) {
                        return size() > 500;
                    }
                });

        Path of(Path file, java.util.concurrent.Callable<Path> make) throws IOException, InterruptedException {
            java.util.concurrent.CompletableFuture<Path> mine = new java.util.concurrent.CompletableFuture<>();
            // Registered first, worked after: never inside the map's own compute (ui/media lesson on ImageCache).
            java.util.concurrent.CompletableFuture<Path> theirs = made.putIfAbsent(file, mine);
            if (theirs == null) {
                try {
                    mine.complete(make.call());
                } catch (Exception e) {
                    mine.completeExceptionally(e);
                }
            }
            try {
                return (theirs == null ? mine : theirs).get();
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            }
        }

        /** Done with {@code file}: its result is gone (the cache trimmed it), and it may be made again. */
        void forget(Path file) {
            made.remove(file);
        }
    }

    private static final Once ONCE = new Once();

    /**
     * The animated WebP of the Lottie sticker {@code lottie}, made once beside it (the cache trims both together, as
     * the name starts the same). Blocking.
     */
    public static Path asWebp(Path lottie) throws IOException, InterruptedException {
        Path webp = lottie.resolveSibling(lottie.getFileName() + ".webp");
        if (Files.isRegularFile(webp) && Files.size(webp) > 0) return webp;
        java.util.concurrent.Callable<Path> make = () -> Files.isRegularFile(webp) && Files.size(webp) > 0 ? webp : toWebp(lottie, webp, p -> { });
        Path made = ONCE.of(lottie, make);
        if (Files.isRegularFile(made)) return made;
        ONCE.forget(lottie); // made before, trimmed since
        return ONCE.of(lottie, make);
    }

    /** {@code lottie}'s JSON at {@code out}: what Telegram's bridge takes (it gzips it itself). */
    public static Path plainJson(Path lottie, Path out) throws IOException {
        byte[] json = json(lottie);
        check(json);
        return Files.write(out, json);
    }

    /** A PAM file (RGB_ALPHA, 8 bits per sample), which img2webp reads without a PNG library. */
    static void writePam(BufferedImage img, Path to) throws IOException {
        if (img == null) throw new IOException(I18n.t("video.webpFailed"));
        int w = img.getWidth(), h = img.getHeight();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(to))) {
            out.write(("P7\nWIDTH " + w + "\nHEIGHT " + h + "\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n")
                    .getBytes(StandardCharsets.US_ASCII));
            int[] row = new int[w];
            byte[] bytes = new byte[w * 4];
            for (int y = 0; y < h; y++) {
                img.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    int p = row[x];
                    bytes[x * 4] = (byte) (p >> 16);
                    bytes[x * 4 + 1] = (byte) (p >> 8);
                    bytes[x * 4 + 2] = (byte) p;
                    bytes[x * 4 + 3] = (byte) (p >>> 24);
                }
                out.write(bytes);
            }
        }
    }
}

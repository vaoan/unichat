package app.unichat.ui.media;

import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Showing;
import app.unichat.ui.Theme;
import app.unichat.support.I18n;
import app.unichat.support.Threads;
import app.unichat.gif.GifFrames;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Animated stickers/GIFs, decoded here and played by {@link #play}, which stops when the view leaves the screen.
 * GIFs don't use JavaFX's own animation: it keeps every frame of every GIF ever shown alive until the app quits
 * (its timeline never stops and holds the Image), which filled the heap. WebP (WhatsApp stickers) JavaFX can't read
 * at all: its frames are patches placed on a canvas (ANMF chunks: offset, duration, blend, dispose), composited here.
 * Frames are scaled to the display size, capped per animation, and the cache is capped in bytes.
 */
public final class AnimatedImage {
    private AnimatedImage() {}

    /** Decoding happens here, off the FX thread; two at a time is plenty for a chat screen. */
    public static final java.util.concurrent.ExecutorService DECODER = java.util.concurrent.Executors.newFixedThreadPool(2, Threads.daemon("image-decoder"));

    /** Decoded animation: equal-size frames and how long each one stays, in ms. */
    public record Frames(List<Image> images, List<Integer> durationsMs, Source source) {
        public Frames(List<Image> images, List<Integer> durationsMs) {
            this(images, durationsMs, null);
        }

        public boolean animated() { return images.size() > 1; }

        /** Pixel memory (4 bytes per pixel). */
        long bytes() {
            long b = 0;
            for (Image i : images) b += (long) i.getWidth() * (long) i.getHeight() * 4;
            return b;
        }
    }

    /** The file and size frames were decoded from, so they can be let go and read again from disk. */
    public record Source(Path file, int maxW, int maxH) {
        Frames decode() throws IOException {
            return AnimatedImage.decode(file, maxW, maxH);
        }
    }

    /** How many times an animation's frames were read again from its file (tests). */
    static final java.util.concurrent.atomic.AtomicInteger RELOADS = new java.util.concurrent.atomic.AtomicInteger();

    private static final String TIMELINE = "unichat.animation";
    private static final String COVER = "unichat.cover";
    /** Animations that can be seen now, to look again when a sheet covers their window or leaves. */
    private static final java.util.Set<Playing> SHOWN = new java.util.HashSet<>();

    /**
     * {@code over} (the window's overlay with a sheet in it) covers {@code scene}, or nothing does (null): what's behind
     * stands still meanwhile, dimmed anyway. Each of its frames made JavaFX draw the card's shadow again over it, and on
     * the Mac stickers behind a sheet flashed black.
     */
    public static void cover(javafx.scene.Scene scene, javafx.scene.Node over) {
        if (scene == null) return;
        if (over == null) scene.getProperties().remove(COVER);
        else scene.getProperties().put(COVER, over);
        for (Playing p : List.copyOf(SHOWN)) if (p.view.getScene() == scene) p.recheck.request();
        for (Runnable r : List.copyOf(COVER_WATCHERS)) r.run();
    }

    /** What else stops behind a sheet (the inline videos' players), told when one opens or closes. FX thread. */
    private static final java.util.Set<Runnable> COVER_WATCHERS = new java.util.LinkedHashSet<>();

    static void watchCover(Runnable r) { COVER_WATCHERS.add(r); }

    static void unwatchCover(Runnable r) { COVER_WATCHERS.remove(r); }

    /** Whether {@code n} can be seen now: on screen ({@link #onScreen}) and not behind a sheet ({@link #cover}). */
    static boolean seen(javafx.scene.Node n) {
        return onScreen(n) && !covered(n);
    }

    /** Behind what covers its window ({@link #cover}). */
    private static boolean covered(javafx.scene.Node n) {
        javafx.scene.Scene sc = n.getScene();
        if (sc == null || !(sc.getProperties().get(COVER) instanceof javafx.scene.Node over)) return false;
        for (javafx.scene.Node p = n; p != null; p = p.getParent()) if (p == over) return false;
        return true;
    }

    /** Whether {@code n} can be seen now: its window showing and not minimized, every ancestor visible, inside the window. */
    public static boolean onScreen(javafx.scene.Node n) {
        javafx.scene.Scene sc = n.getScene();
        if (sc == null) return false;
        javafx.stage.Window w = sc.getWindow();
        if (w == null || !w.isShowing() || (w instanceof javafx.stage.Stage st && st.isIconified())) return false;
        for (javafx.scene.Node p = n; p != null; p = p.getParent()) if (!p.isVisible() || p.getOpacity() == 0) return false;
        javafx.geometry.Bounds b = n.localToScene(n.getBoundsInLocal());
        if (!(b.getMaxX() > 0 && b.getMaxY() > 0 && b.getMinX() < sc.getWidth() && b.getMinY() < sc.getHeight())) return false;
        // Inside the window but clipped away: scrolled out of a ScrollPane's or list's viewport.
        for (javafx.scene.Node p = n.getParent(); p != null; p = p.getParent()) {
            if (p.getClip() != null && !p.localToScene(p.getClip().getBoundsInParent()).intersects(b)) return false;
        }
        return true;
    }

    /** Most frames kept of one animation; longer ones keep every n-th frame (same total duration). */
    static final int MAX_FRAMES = 120;
    /** Most pixel memory for one animation: big or long ones drop frames until they fit. */
    static final long MAX_ANIMATION_BYTES = 24L << 20;
    /** Every decoded animation together; the least recently used go first. */
    static final long CACHE_BYTES = 64L << 20;

    /** One ANMF chunk of an animated WebP. */
    record AnimFrame(int x, int y, int width, int height, int durationMs, boolean noBlend, boolean disposeToBackground) {}

    /** Parsed RIFF container: canvas size and frame layout (empty list for a still WebP). */
    record WebpLayout(int canvasWidth, int canvasHeight, List<AnimFrame> frames) {}

    private static final LinkedHashMap<String, Frames> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static long cachedBytes;

    /** What's already decoded for this file at this size, or null: shown at once, with no blank moment. */
    static Frames decoded(Path file, int maxW, int maxH) {
        return cached(file + "|" + maxW + "x" + maxH);
    }

    /**
     * A video sticker (WebM, which JavaFX can't play): its frames straight from ffmpeg at the size shown, transparency kept
     * ({@code video/VideoFrames}), within the same caps as a GIF's. No file in between: each was made a GIF first, up to
     * 21 MB and seconds each. Blocking.
     */
    public static Frames decodeVideo(Path file, int maxW, int maxH) throws IOException {
        Frames hit = decoded(file, maxW, maxH);
        if (hit != null) return hit;
        return remember(file + "|" + maxW + "x" + maxH, video(file, maxW, maxH, MAX_FRAMES));
    }

    private static Frames video(Path file, int maxW, int maxH, int maxFrames) throws IOException {
        try {
            app.unichat.video.AnimatedWebp.Probe probe = app.unichat.video.VideoFrames.probe(file);
            if (probe.width() <= 0 || probe.height() <= 0) throw new IOException(I18n.t("image.invalid"));
            double fit = Math.min(1.0, Math.min((double) maxW / probe.width(), (double) maxH / probe.height()));
            int count = probe.seconds() > 0 ? (int) Math.min(maxFrames, Math.ceil(probe.seconds() * (probe.fps() > 0 ? probe.fps() : 30))) : maxFrames;
            double s = fit * shrinkToKeep(count, (int) Math.round(probe.width() * fit), (int) Math.round(probe.height() * fit));
            int w = Math.max(1, (int) Math.round(probe.width() * s)), h = Math.max(1, (int) Math.round(probe.height() * s));
            List<Image> images = new ArrayList<>();
            // Capped by bytes too: the probe is what ffmpeg read of the file, and a file can say less than it holds.
            int frames = (int) Math.max(1, Math.min(maxFrames, MAX_ANIMATION_BYTES / ((long) w * h * 4)));
            int ms = app.unichat.video.VideoFrames.read(file, probe, w, h, frames, rgba -> images.add(fromRgba(rgba, w, h)));
            return new Frames(List.copyOf(images), java.util.Collections.nCopies(images.size(), ms), new Source(file, maxW, maxH));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(I18n.t("image.invalid"), e);
        }
    }

    /** RGBA bytes as an image (straight, not premultiplied, alpha). */
    private static Image fromRgba(byte[] rgba, int w, int h) {
        int[] argb = new int[w * h];
        for (int i = 0, b = 0; i < argb.length; i++, b += 4) {
            argb[i] = (rgba[b + 3] & 0xFF) << 24 | (rgba[b] & 0xFF) << 16 | (rgba[b + 1] & 0xFF) << 8 | (rgba[b + 2] & 0xFF);
        }
        javafx.scene.image.WritableImage img = new javafx.scene.image.WritableImage(w, h);
        img.getPixelWriter().setPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, w);
        return img;
    }

    /** A plain picture (a PNG sticker, say), remembered like an animation. Blocking. */
    public static Frames decodePlain(Path file, int maxW, int maxH) throws IOException {
        Frames hit = decoded(file, maxW, maxH);
        if (hit != null) return hit;
        Image img = new Image(file.toUri().toString(), maxW, maxH, true, true, false);
        if (img.isError()) throw new IOException(I18n.t("image.invalid"));
        return remember(file + "|" + maxW + "x" + maxH, new Frames(List.of(img), List.of(0), new Source(file, maxW, maxH)));
    }

    private static Frames cached(String key) {
        synchronized (CACHE) {
            return CACHE.get(key);
        }
    }

    private static Frames remember(String key, Frames f) {
        synchronized (CACHE) {
            Frames old = CACHE.put(key, f);
            if (old != null) cachedBytes -= old.bytes();
            cachedBytes += f.bytes();
            for (var it = CACHE.entrySet().iterator(); cachedBytes > CACHE_BYTES && it.hasNext(); ) {
                var e = it.next();
                if (e.getValue() == f) continue;
                cachedBytes -= e.getValue().bytes();
                it.remove();
            }
        }
        return f;
    }

    /**
     * How much smaller frames of {@code w}x{@code h} (decoded at twice their size on screen) are made so {@code count} of
     * them fit {@link #MAX_ANIMATION_BYTES}: never below half, their size on screen. Dropping frames made a sticker play at
     * half its frame rate; a little less sharpness keeps its motion.
     */
    static double shrinkToKeep(int count, int w, int h) {
        double bytes = (double) count * Math.max(1, w) * Math.max(1, h) * 4;
        if (bytes <= MAX_ANIMATION_BYTES) return 1.0;
        return Math.max(0.5, Math.sqrt(MAX_ANIMATION_BYTES / bytes) * 0.98); // a margin: rounding sizes up mustn't go over
    }

    /** Keep 1 of every {@code step} frames so an animation of {@code count} frames of {@code w}x{@code h} fits the caps. */
    static int step(int count, int w, int h) {
        long perFrame = Math.max(1L, (long) w * h * 4);
        int byBytes = (int) Math.max(1, MAX_ANIMATION_BYTES / perFrame);
        int keep = Math.max(1, Math.min(MAX_FRAMES, byBytes));
        return Math.max(1, (count + keep - 1) / keep);
    }

    /**
     * Durations of the frames kept when only every {@code step}-th one is shown: each kept frame also stays for the
     * ones dropped after it, so the animation lasts as long as before.
     */
    static List<Integer> sample(List<Integer> delays, int step) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < delays.size(); i++) {
            if (i % step == 0) out.add(delays.get(i));
            else out.set(out.size() - 1, out.get(out.size() - 1) + delays.get(i));
        }
        return List.copyOf(out);
    }

    /** Decodes an animated (or still) GIF scaled to fit {@code maxW}x{@code maxH}. Blocking: call off the FX thread. */
    public static Frames decodeGif(Path file, int maxW, int maxH) throws IOException {
        String key = file + "|" + maxW + "x" + maxH;
        Frames hit = cached(key);
        if (hit != null) return hit;
        int count = GifFrames.count(file);
        List<Image> images = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        int[] step = {1};
        int[] fit = {maxW, maxH};
        GifFrames.read(file, f -> {
            int i = delays.size();
            delays.add(f.delayMs());
            BufferedImage scaled = i % step[0] == 0 ? scale(f.image(), fit[0], fit[1]) : null;
            if (i == 0) {
                double s = shrinkToKeep(count, scaled.getWidth(), scaled.getHeight()); // keep the frames, lose a little sharpness
                if (s < 1) {
                    fit[0] = (int) Math.round(scaled.getWidth() * s);
                    fit[1] = (int) Math.round(scaled.getHeight() * s);
                    scaled = scale(scaled, fit[0], fit[1]);
                }
                step[0] = step(count, scaled.getWidth(), scaled.getHeight());
            }
            if (scaled != null) images.add(toFx(scaled));
        });
        if (images.isEmpty()) throw new IOException(I18n.t("image.gifEmpty"));
        return remember(key, new Frames(List.copyOf(images), sample(delays, step[0]), new Source(file, maxW, maxH)));
    }

    /** All the frames of an animated WebP, GIF, video sticker (WebM) or GIF sent as an MP4, or the one of a plain picture, whatever the file claims to be. Blocking. */
    public static Frames decode(Path file, int maxW, int maxH) throws IOException {
        boolean webm = app.unichat.support.ImageHeaders.isVideo(file), webp = !webm && isWebp(file), gif = !webm && !webp && isGif(file);
        if (!webm && !webp && !gif) return decodePlain(file, maxW, maxH);
        Frames hit = decoded(file, maxW, maxH);
        if (hit != null) return hit;
        // What it may hold while it's decoded: its frames (capped) and the raw ones they're made from.
        long need = 2 * Math.min((long) maxW * maxH * 4 * MAX_FRAMES, MAX_ANIMATION_BYTES);
        try (DecodeBudget.Lease lease = DecodeBudget.SHARED.take(need)) {
            if (webm) return decodeVideo(file, maxW, maxH);
            return webp ? decodeWebp(file, maxW, maxH) : decodeGif(file, maxW, maxH);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(I18n.t("image.invalid"), e);
        }
    }

    /**
     * A still picture of {@code file} fitting {@code maxW}x{@code maxH}: a GIF shows its first frame (never JavaFX's
     * own animation, which leaks). For thumbnails and avatars; blocking, call off the FX thread.
     */
    public static Image still(Path file, int maxW, int maxH) throws IOException {
        if (app.unichat.support.ImageHeaders.isVideo(file)) return video(file, maxW, maxH, 1).images().getFirst();
        if (isBigWebp(file)) return new Image(shrunk(file).toUri().toString(), maxW, maxH, true, true, false);
        if (isGif(file) || isWebp(file)) { // JavaFX can't read WebP; ImageIO can (TwelveMonkeys), its first frame if animated
            BufferedImage first;
            try {
                first = ImageIO.read(file.toFile());
            } catch (IOException e) { // a WebP TwelveMonkeys can't read: libwebp can (composeWebp)
                if (!isWebp(file)) throw e;
                first = composeWebp(file, maxW, maxH).images().getFirst();
            }
            if (first == null) throw new IOException(I18n.t("image.gifEmpty"));
            return toFx(scale(first, maxW, maxH));
        }
        return new Image(file.toUri().toString(), maxW, maxH, true, true, false);
    }

    /**
     * Above this a WebP is decoded in another process: TwelveMonkeys takes about 25 bytes per pixel (600 MB for a
     * 24-megapixel photo), against Unichat's 1 GB. Stickers and ordinary pictures are far below.
     */
    static final long MAX_WEBP_PIXELS = 3_000_000;

    /**
     * Canvas pixels times frames an animation may have to be decoded here: every frame is held whole (anim_dump writes
     * each to disk first), about 4 bytes a pixel. A sticker (512x512, 120 frames) is half of it.
     */
    static final long MAX_ANIMATION_PIXELS = 64L << 20;

    /**
     * A WebP too big to decode here: a still one past {@link #MAX_WEBP_PIXELS}, or an animated one past
     * {@link #MAX_ANIMATION_PIXELS} (a few hundred bytes from anyone can declare 300 frames of 8000x8000). Sizes come
     * from the headers, nothing is decoded.
     */
    static boolean isBigWebp(Path file) throws IOException {
        int[] wh = webpSize(file);
        if (wh == null) return false;
        long pixels = (long) wh[0] * wh[1];
        if (!webpAnimated(file)) return pixels > MAX_WEBP_PIXELS;
        if (pixels <= MAX_ANIMATION_PIXELS / MAX_FRAMES) return false; // a sticker: no need to count
        return pixels * parseLayout(Files.readAllBytes(file)).frames().size() > MAX_ANIMATION_PIXELS;
    }

    /** Width and height from a WebP's first chunk (VP8X, VP8 or VP8L), or null if it isn't one. */
    static int[] webpSize(Path file) throws IOException {
        byte[] d;
        try (var in = Files.newInputStream(file)) {
            d = in.readNBytes(30);
        }
        if (d.length < 30 || d[0] != 'R' || d[1] != 'I' || d[8] != 'W' || d[9] != 'E') return null;
        String chunk = new String(d, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return switch (chunk) {
            case "VP8X" -> new int[]{1 + u24(d, 24), 1 + u24(d, 27)};
            case "VP8 " -> new int[]{((d[26] & 0xFF) | (d[27] & 0xFF) << 8) & 0x3FFF, ((d[28] & 0xFF) | (d[29] & 0xFF) << 8) & 0x3FFF};
            case "VP8L" -> new int[]{1 + (((d[22] & 0x3F) << 8) | (d[21] & 0xFF)),
                    1 + (((d[24] & 0x0F) << 10) | ((d[23] & 0xFF) << 2) | ((d[22] & 0xC0) >> 6))};
            default -> null;
        };
    }

    /** The animation flag of a VP8X header. */
    private static boolean webpAnimated(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] d = in.readNBytes(21);
            return d.length == 21 && d[12] == 'V' && d[15] == 'X' && (d[20] & 0x02) != 0;
        }
    }

    private static Path shrunk(Path file) throws IOException {
        try {
            return app.unichat.video.BigPictures.shrunk(file);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public static boolean isGif(Path file) throws IOException {
        return "image/gif".equals(app.unichat.support.ImageHeaders.pictureMime(file));
    }

    public static boolean isWebp(Path file) throws IOException {
        return "image/webp".equals(app.unichat.support.ImageHeaders.pictureMime(file));
    }

    /** Reads the RIFF chunk list. Pure function of the bytes (unit-tested). */
    static WebpLayout parseLayout(byte[] d) {
        int canvasW = 0, canvasH = 0;
        List<AnimFrame> frames = new ArrayList<>();
        int i = 12;
        while (i + 8 <= d.length) {
            String type = new String(d, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = ByteBuffer.wrap(d, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int p = i + 8;
            if (type.equals("VP8X") && p + 10 <= d.length) {
                canvasW = u24(d, p + 4) + 1;
                canvasH = u24(d, p + 7) + 1;
            } else if (type.equals("ANMF") && p + 16 <= d.length) {
                int flags = d[p + 15] & 0xFF;
                frames.add(new AnimFrame(u24(d, p) * 2, u24(d, p + 3) * 2, u24(d, p + 6) + 1, u24(d, p + 9) + 1,
                        u24(d, p + 12), (flags & 0x02) != 0, (flags & 0x01) != 0));
            }
            if (size < 0) break;
            i = p + size + (size & 1);
        }
        return new WebpLayout(canvasW, canvasH, frames);
    }

    private static int u24(byte[] d, int p) {
        return (d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8 | (d[p + 2] & 0xFF) << 16;
    }

    /** Composited frames before conversion to JavaFX images (unit-tested without a display). */
    record RawFrames(List<BufferedImage> images, List<Integer> durationsMs) {}

    /** Decodes a WebP (still or animated) scaled to fit {@code maxW}x{@code maxH}. Blocking: call off the FX thread. */
    public static Frames decodeWebp(Path file, int maxW, int maxH) throws IOException {
        String key = file + "|" + maxW + "x" + maxH;
        Frames hit = cached(key);
        if (hit != null) return hit;
        RawFrames raw = composeWebp(file, maxW, maxH);
        List<Image> images = new ArrayList<>();
        int count = raw.images().size();
        int w = count == 0 ? 1 : raw.images().get(0).getWidth(), h = count == 0 ? 1 : raw.images().get(0).getHeight();
        double s = shrinkToKeep(count, w, h);
        int sw = (int) Math.round(w * s), sh = (int) Math.round(h * s);
        int step = count == 0 ? 1 : step(count, sw, sh);
        for (int i = 0; i < count; i += step) images.add(toFx(s < 1 ? scale(raw.images().get(i), sw, sh) : raw.images().get(i)));
        return remember(key, new Frames(List.copyOf(images), sample(raw.durationsMs(), step), new Source(file, maxW, maxH)));
    }

    static RawFrames composeWebp(Path file, int maxW, int maxH) throws IOException {
        return composeWebp(file, maxW, maxH, JAVA_WEBP);
    }

    /** {@link #composeWebp} with {@code decoder} as the Java decoder; when it fails, libwebp's anim_dump reads the file. */
    static RawFrames composeWebp(Path file, int maxW, int maxH, WebpOpener decoder) throws IOException {
        try {
            return composeWithJava(file, maxW, maxH, decoder);
        } catch (IOException e) {
            // TwelveMonkeys' VP8 decoder runs out of data on some files libwebp reads fine (Discord's link previews):
            // nothing showed at all. libwebp's anim_dump reads them, still ones too.
            List<BufferedImage> images = app.unichat.video.AnimDump.frames(file, f -> scale(f, maxW, maxH));
            if (images == null || images.isEmpty()) throw e;
            int ms = images.size() > 1 ? 100 : 0;
            return new RawFrames(List.copyOf(images), java.util.Collections.nCopies(images.size(), ms));
        }
    }

    /** A WebP's frames read in Java (TwelveMonkeys), one reader for the whole file; a test hands one that fails as it did. */
    interface WebpReader extends AutoCloseable {
        BufferedImage read(int i) throws IOException;

        @Override
        void close() throws IOException;
    }

    /** Opens a {@link WebpReader} on a file. */
    @FunctionalInterface
    interface WebpOpener {
        WebpReader open(Path file) throws IOException;
    }

    private static final WebpOpener JAVA_WEBP = file -> {
        ImageReader reader = new WebPImageReaderSpi().createReaderInstance();
        ImageInputStream in = ImageIO.createImageInputStream(file.toFile());
        reader.setInput(in);
        return new WebpReader() {
            @Override
            public BufferedImage read(int i) throws IOException {
                return reader.read(i);
            }

            @Override
            public void close() throws IOException {
                reader.dispose();
                in.close();
            }
        };
    };

    private static RawFrames composeWithJava(Path file, int maxW, int maxH, WebpOpener decoder) throws IOException {
        if (isBigWebp(file)) { // a phone's photo, not a sticker: never decoded here (see BigPictures)
            BufferedImage small = ImageIO.read(shrunk(file).toFile());
            if (small == null) throw new IOException(I18n.t("image.invalid"));
            return new RawFrames(List.of(scale(small, maxW, maxH)), List.of(0));
        }
        WebpLayout layout = parseLayout(Files.readAllBytes(file));
        if (!layout.frames().isEmpty()) {
            // libwebp composites it as a browser does; the Java decoder below gets some lossy frames' colours wrong (black
            // blotches over a sticker). It's only the fallback when anim_dump is missing or fails.
            List<BufferedImage> libwebp = app.unichat.video.AnimDump.frames(file, f -> scale(f, maxW, maxH));
            if (libwebp != null && libwebp.size() == layout.frames().size()) {
                List<BufferedImage> images = new ArrayList<>();
                List<Integer> durations = new ArrayList<>();
                for (int i = 0; i < libwebp.size(); i++) {
                    images.add(libwebp.get(i));
                    int ms = layout.frames().get(i).durationMs();
                    durations.add(ms <= 10 ? 100 : ms); // like browsers: very short/zero durations play at 100 ms
                }
                return new RawFrames(List.copyOf(images), List.copyOf(durations));
            }
        }
        try (WebpReader reader = decoder.open(file)) {
            List<BufferedImage> images = new ArrayList<>();
            List<Integer> durations = new ArrayList<>();
            if (layout.frames().isEmpty()) {
                images.add(scale(reader.read(0), maxW, maxH));
                durations.add(0);
            } else {
                BufferedImage canvas = new BufferedImage(layout.canvasWidth(), layout.canvasHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = canvas.createGraphics();
                AnimFrame previous = null;
                for (int i = 0; i < layout.frames().size(); i++) {
                    if (previous != null && previous.disposeToBackground()) clear(g, previous);
                    AnimFrame f = layout.frames().get(i);
                    BufferedImage patch = reader.read(i);
                    if (f.noBlend()) clear(g, f);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.drawImage(patch, f.x(), f.y(), null);
                    images.add(scale(canvas, maxW, maxH));
                    // Like browsers: very short/zero durations play at 100 ms.
                    durations.add(f.durationMs() <= 10 ? 100 : f.durationMs());
                    previous = f;
                }
                g.dispose();
            }
            return new RawFrames(List.copyOf(images), List.copyOf(durations));
        }
    }

    private static void clear(Graphics2D g, AnimFrame f) {
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(f.x(), f.y(), f.width(), f.height());
    }

    /** Copy scaled to fit inside maxW x maxH (never upscaled). */
    static BufferedImage scale(BufferedImage src, int maxW, int maxH) {
        double s = Math.min(1.0, Math.min(maxW / (double) src.getWidth(), maxH / (double) src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * s)), h = Math.max(1, (int) Math.round(src.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static Image toFx(BufferedImage img) {
        return SwingFXUtils.toFXImage(img, null);
    }

    /**
     * Shows {@code frames} in {@code view}, moving only while it's on screen and fits {@link AnimationBudget}. Frames
     * read from a file ({@link Frames#source}) are let go while it doesn't move (scrolled away, hidden, past the budget),
     * keeping the picture it shows, and read again (the cache, else the disk) when it comes back: a sticker set kept
     * every tile's frames, still or not, and ran a 1 GB heap out.
     */
    public static void play(ImageView view, Frames frames) {
        // A view that gets a new animation (cell reused, thumbnail then original) stops the previous one.
        if (view.getProperties().remove(TIMELINE) instanceof Playing old) old.end();
        view.setImage(frames.images().get(0));
        if (!frames.animated() || Theme.reduceMotion()) return;
        Playing playing = new Playing(view, frames);
        view.getProperties().put(TIMELINE, playing);
        // Only while it can be seen: a closed viewer keeps its scene, and its GIF ran on for good, holding the budget.
        playing.watch = Showing.whileShowing(view, playing::shown, playing::hidden);
    }

    /** Whether {@code view} holds an animation's frames now (moving, or one that can't be read again). */
    static boolean holdsFrames(ImageView view) {
        return view.getProperties().get(TIMELINE) instanceof Playing p && p.frames != null;
    }

    /** Whether {@code view}'s animation is moving now. */
    static boolean moving(ImageView view) {
        return view.getProperties().get(TIMELINE) instanceof Playing p && p.timeline != null
                && p.timeline.getStatus() == Animation.Status.RUNNING;
    }

    /**
     * A view's animation: moving while its window shows it on screen and {@link AnimationBudget} has room, its frames let
     * go otherwise (when they can be read again) and read again on coming back.
     */
    private static final class Playing {
        private final ImageView view;
        private final Source source;
        private final long bytes;
        private final FxCoalescer recheck;
        private final javafx.beans.value.ChangeListener<javafx.scene.transform.Transform> moved;
        private Frames frames;
        private Timeline timeline;
        private boolean showing, loading, ended;
        /** Budget taken for frames being read again, not yet moving. */
        private boolean reserved;
        /** Whether it can be seen; cancelled when the view is handed another animation. */
        private Showing.Watch watch;

        Playing(ImageView view, Frames frames) {
            this.view = view;
            this.frames = frames;
            this.source = frames.source();
            this.bytes = frames.bytes();
            this.recheck = new FxCoalescer(this::follow);
            this.moved = (o, before, now) -> recheck.request();
        }

        void shown() {
            showing = true;
            SHOWN.add(this);
            view.localToSceneTransformProperty().addListener(moved); // scrolled into or out of view
            follow();
        }

        void hidden() {
            showing = false;
            SHOWN.remove(this);
            view.localToSceneTransformProperty().removeListener(moved);
            follow();
        }

        /** Moves when it can be seen and fits the budget; otherwise stops, and lets its frames go if they can be read again. */
        private void follow() {
            if (ended) return;
            boolean wanted = showing && seen(view);
            if (wanted && !running()) {
                // Its room is taken before its frames are read again: past the budget, every look (a scroll step, a
                // sheet closing, the window back) read them and threw them away.
                if (frames == null) {
                    if (!reserved && AnimationBudget.SHARED.take(bytes)) {
                        reserved = true;
                        reload();
                    }
                } else if (reserved || AnimationBudget.SHARED.take(bytes)) {
                    reserved = false;
                    timeline().play();
                } else {
                    letGo();
                }
            } else if (!wanted) {
                if (running() || reserved) AnimationBudget.SHARED.release(bytes);
                reserved = false;
                if (timeline != null) timeline.stop();
                letGo();
            }
        }

        private boolean running() {
            return timeline != null && timeline.getStatus() == Animation.Status.RUNNING;
        }

        private Timeline timeline() {
            if (timeline != null) return timeline;
            timeline = new Timeline();
            double at = 0;
            for (int i = 0; i < frames.images().size(); i++) {
                Image img = frames.images().get(i);
                timeline.getKeyFrames().add(new KeyFrame(Duration.millis(at), e -> {
                    // Off screen (scrolled away, hidden tab, window minimized): no new frame, so nothing gets redrawn.
                    // Every running animation used to repaint the window ~60 times a second (a third of a core).
                    if (onScreen(view)) view.setImage(img);
                }));
                at += frames.durationsMs().get(i);
            }
            timeline.getKeyFrames().add(new KeyFrame(Duration.millis(at)));
            timeline.setCycleCount(Timeline.INDEFINITE);
            return timeline;
        }

        /** Keeps only the picture on screen; nothing to let go when the frames can't be read again. */
        private void letGo() {
            if (source == null) return;
            timeline = null;
            frames = null;
        }

        private void reload() {
            if (loading) return;
            loading = true;
            RELOADS.incrementAndGet();
            DECODER.execute(() -> {
                Frames again;
                try {
                    again = source.decode();
                } catch (IOException e) {
                    again = null; // the file went (cache trimmed): it stays still
                }
                Frames read = again;
                javafx.application.Platform.runLater(() -> {
                    loading = false;
                    if (read == null || !read.animated() || ended) {
                        if (reserved) AnimationBudget.SHARED.release(bytes);
                        reserved = false;
                        return;
                    }
                    frames = read;
                    follow();
                });
            });
        }

        /** Replaced by another animation: stopped, and no longer following the view's window. */
        void end() {
            if (watch != null) watch.cancel();
            hidden();
            ended = true;
        }
    }
}

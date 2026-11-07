package app.unichat.ui;

import app.unichat.support.I18n;
import app.unichat.support.Threads;
import app.unichat.gif.GifFrames;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
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
    public record Frames(List<Image> images, List<Integer> durationsMs) {
        public boolean animated() { return images.size() > 1; }

        /** Pixel memory (4 bytes per pixel). */
        long bytes() {
            long b = 0;
            for (Image i : images) b += (long) i.getWidth() * (long) i.getHeight() * 4;
            return b;
        }
    }

    private static final String TIMELINE = "unichat.animation";

    /** Whether {@code n} can be seen now: its window showing and not minimized, every ancestor visible, inside the window. */
    static boolean onScreen(javafx.scene.Node n) {
        javafx.scene.Scene sc = n.getScene();
        if (sc == null) return false;
        javafx.stage.Window w = sc.getWindow();
        if (w == null || !w.isShowing() || (w instanceof javafx.stage.Stage st && st.isIconified())) return false;
        for (javafx.scene.Node p = n; p != null; p = p.getParent()) if (!p.isVisible() || p.getOpacity() == 0) return false;
        javafx.geometry.Bounds b = n.localToScene(n.getBoundsInLocal());
        return b.getMaxX() > 0 && b.getMaxY() > 0 && b.getMinX() < sc.getWidth() && b.getMinY() < sc.getHeight();
    }

    /** Most frames kept of one animation; longer ones keep every n-th frame (same total duration). */
    static final int MAX_FRAMES = 120;
    /** Most pixel memory for one animation: big or long ones drop frames until they fit. */
    static final long MAX_ANIMATION_BYTES = 24L << 20;
    /** Every decoded animation together; the least recently used go first. */
    static final long CACHE_BYTES = 128L << 20;

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

    /** A plain picture (a PNG sticker, say), remembered like an animation. Blocking. */
    static Frames decodePlain(Path file, int maxW, int maxH) throws IOException {
        Frames hit = decoded(file, maxW, maxH);
        if (hit != null) return hit;
        Image img = new Image(file.toUri().toString(), maxW, maxH, true, true, false);
        if (img.isError()) throw new IOException(I18n.t("image.invalid"));
        return remember(file + "|" + maxW + "x" + maxH, new Frames(List.of(img), List.of(0)));
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
        GifFrames.read(file, f -> {
            int i = delays.size();
            delays.add(f.delayMs());
            BufferedImage scaled = i % step[0] == 0 ? scale(f.image(), maxW, maxH) : null;
            if (i == 0) step[0] = step(count, scaled.getWidth(), scaled.getHeight());
            if (scaled != null) images.add(toFx(scaled));
        });
        if (images.isEmpty()) throw new IOException(I18n.t("image.gifEmpty"));
        return remember(key, new Frames(List.copyOf(images), sample(delays, step[0])));
    }

    /** All the frames of an animated WebP or GIF, or the one of a plain picture, whatever the file claims to be. Blocking. */
    public static Frames decode(Path file, int maxW, int maxH) throws IOException {
        if (isWebp(file)) return decodeWebp(file, maxW, maxH);
        if (isGif(file)) return decodeGif(file, maxW, maxH);
        return decodePlain(file, maxW, maxH);
    }

    /**
     * A still picture of {@code file} fitting {@code maxW}x{@code maxH}: a GIF shows its first frame (never JavaFX's
     * own animation, which leaks). For thumbnails and avatars; blocking, call off the FX thread.
     */
    public static Image still(Path file, int maxW, int maxH) throws IOException {
        if (isGif(file)) {
            BufferedImage first = ImageIO.read(file.toFile());
            if (first == null) throw new IOException(I18n.t("image.gifEmpty"));
            return toFx(scale(first, maxW, maxH));
        }
        return new Image(file.toUri().toString(), maxW, maxH, true, true, false);
    }

    public static boolean isGif(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] h = in.readNBytes(4);
            return h.length == 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8';
        }
    }

    public static boolean isWebp(Path file) throws IOException {
        byte[] head = new byte[12];
        try (var in = Files.newInputStream(file)) {
            if (in.readNBytes(head, 0, 12) < 12) return false;
        }
        return head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
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
        int step = raw.images().isEmpty() ? 1
                : step(raw.images().size(), raw.images().get(0).getWidth(), raw.images().get(0).getHeight());
        for (int i = 0; i < raw.images().size(); i += step) images.add(toFx(raw.images().get(i)));
        return remember(key, new Frames(List.copyOf(images), sample(raw.durationsMs(), step)));
    }

    static RawFrames composeWebp(Path file, int maxW, int maxH) throws IOException {
        WebpLayout layout = parseLayout(Files.readAllBytes(file));
        ImageReader reader = new WebPImageReaderSpi().createReaderInstance();
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            reader.setInput(in);
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
        } finally {
            reader.dispose();
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
     * Shows {@code frames} in {@code view}, looping, unless motion is reduced (then the first frame only).
     * The timeline stops by itself when the view leaves the scene.
     */
    public static void play(ImageView view, Frames frames) {
        // A view that gets a new animation (cell reused, thumbnail then original) stops the previous one.
        if (view.getProperties().remove(TIMELINE) instanceof Timeline old) old.stop();
        view.setImage(frames.images().get(0));
        if (!frames.animated() || Theme.reduceMotion()) return;
        Timeline t = new Timeline();
        view.getProperties().put(TIMELINE, t);
        double at = 0;
        for (int i = 0; i < frames.images().size(); i++) {
            Image img = frames.images().get(i);
            t.getKeyFrames().add(new KeyFrame(Duration.millis(at), e -> {
                // Off screen (scrolled away, hidden tab, window minimized): no new frame, so nothing gets redrawn.
                // Every running animation used to repaint the window ~60 times a second (a third of a core).
                if (onScreen(view)) view.setImage(img);
            }));
            at += frames.durationsMs().get(i);
        }
        t.getKeyFrames().add(new KeyFrame(Duration.millis(at)));
        t.setCycleCount(Timeline.INDEFINITE);
        view.sceneProperty().addListener((o, a, scene) -> {
            if (view.getProperties().get(TIMELINE) != t) return;
            if (scene == null) t.stop(); else t.play();
        });
        if (view.getScene() != null) t.play();
    }
}

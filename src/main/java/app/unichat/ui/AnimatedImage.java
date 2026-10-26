package app.unichat.ui;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Animated stickers/GIFs. JavaFX animates GIF by itself but can't read WebP at all, and WhatsApp stickers are
 * animated WebP: their frames are patches placed on a canvas (ANMF chunks: offset, duration, blend, dispose),
 * so they're composited here and scaled down to the display size.
 */
public final class AnimatedImage {
    private AnimatedImage() {}

    /** Decoding happens here, off the FX thread; two at a time is plenty for a chat screen. */
    public static final java.util.concurrent.ExecutorService DECODER = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "image-decoder");
        t.setDaemon(true);
        return t;
    });

    /** Decoded animation: equal-size frames and how long each one stays, in ms. */
    public record Frames(List<Image> images, List<Integer> durationsMs) {
        public boolean animated() { return images.size() > 1; }
    }

    /** One ANMF chunk of an animated WebP. */
    record AnimFrame(int x, int y, int width, int height, int durationMs, boolean noBlend, boolean disposeToBackground) {}

    /** Parsed RIFF container: canvas size and frame layout (empty list for a still WebP). */
    record WebpLayout(int canvasWidth, int canvasHeight, List<AnimFrame> frames) {}

    private static final Map<String, Frames> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Frames> e) {
            return size() > 40;
        }
    });

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
        Frames cached = CACHE.get(key);
        if (cached != null) return cached;
        RawFrames raw = composeWebp(file, maxW, maxH);
        List<Image> images = new ArrayList<>();
        for (BufferedImage b : raw.images()) images.add(toFx(b));
        Frames out = new Frames(List.copyOf(images), raw.durationsMs());
        CACHE.put(key, out);
        return out;
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
        view.setImage(frames.images().get(0));
        if (!frames.animated() || Theme.reduceMotion()) return;
        Timeline t = new Timeline();
        double at = 0;
        for (int i = 0; i < frames.images().size(); i++) {
            Image img = frames.images().get(i);
            t.getKeyFrames().add(new KeyFrame(Duration.millis(at), e -> view.setImage(img)));
            at += frames.durationsMs().get(i);
        }
        t.getKeyFrames().add(new KeyFrame(Duration.millis(at)));
        t.setCycleCount(Timeline.INDEFINITE);
        view.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) t.stop(); else t.play();
        });
        if (view.getScene() != null) t.play();
    }
}

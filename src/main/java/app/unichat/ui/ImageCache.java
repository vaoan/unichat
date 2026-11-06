package app.unichat.ui;

import app.unichat.video.WebmStickers;
import app.unichat.service.MediaSource;
import javafx.scene.image.Image;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Avatar photos, decoded once per URL and shared by every size (list 44 px, header 34, messages 28, info 84).
 * Before, each size was its own download: opening a chat showed the initials in the header/info/messages and
 * then the photo — the "flash" on every click. Concurrent requests for one URL share a single load.
 */
final class ImageCache {
    private ImageCache() {}

    /** Decode size: enough for the largest avatar (84 px) on a 2x screen; smaller views scale it down. */
    static final int PX = 168;
    private static final int MAX = 500;

    private static final Map<String, Image> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> e) {
            return size() > MAX;
        }
    });
    private static final Map<String, CompletableFuture<Image>> LOADING = new ConcurrentHashMap<>();
    /** URLs that failed (e.g. bridge-bot avatars on remote servers a "localhost" Synapse can't fetch). */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final long RETRY_AFTER_MS = 10 * 60 * 1000;

    static Image get(String url) {
        return CACHE.get(url);
    }

    /**
     * The photo for {@code url}: cached, already loading, or a new (single) download + decode off the FX thread.
     * Completes with null when it can't be loaded (then the initials stay; retried after a while, not per click).
     */
    static CompletableFuture<Image> load(String url, MediaSource svc) {
        Image cached = CACHE.get(url);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        Long failedAt = FAILED.get(url);
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return CompletableFuture.completedFuture(null);
        return LOADING.computeIfAbsent(url, u -> svc.media(u, PX, PX).thenApplyAsync(path -> {
            // Decoded completely here, so a view never shows a half-loaded (blank) image.
            // Animated avatars (Discord) stay still: JavaFX's own GIF animation never lets the image go.
            Image img;
            try {
                img = AnimatedImage.still(isWebm(path) ? WebmStickers.animated(path, done -> { }) : path, PX, PX);
            } catch (java.io.IOException e) {
                img = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                img = null;
            }
            if (img == null || img.isError()) {
                FAILED.put(u, System.currentTimeMillis());
                return (Image) null;
            }
            CACHE.put(u, img);
            FAILED.remove(u);
            return img;
        }, AnimatedImage.DECODER).exceptionally(err -> {
            FAILED.put(u, System.currentTimeMillis());
            return null;
        }).whenComplete((img, err) -> LOADING.remove(u)));
    }

    /** Telegram's custom emoji can be WebM, which only becomes a picture through ffmpeg. */
    private static boolean isWebm(java.nio.file.Path file) throws java.io.IOException {
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && (head[0] & 0xFF) == 0x1A && head[1] == 0x45 && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
        }
    }

    static int size() {
        return CACHE.size();
    }

    static void clear() {
        CACHE.clear();
        FAILED.clear();
    }
}

package app.unichat.ui.media;

import app.unichat.service.MediaSource;
import app.unichat.support.BoundedMap;
import javafx.scene.image.Image;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Avatar photos, decoded once per URL and tier. Two tiers, as a 168 px image per URL for every size held 4x the
 * pixels a 28 px view shows: SMALL for views up to 44 px (lists, header, messages, reactions), LARGE above (info
 * panel, settings), each decoded at its largest size times the screen's scale. The server is still asked for one
 * 168 px thumbnail per address, so both tiers come from one download. Concurrent requests share a single load.
 */
public final class ImageCache {
    private ImageCache() {}

    /** What the server is asked for: enough for the largest avatar (84 px) on a 2x screen; one file for both tiers. */
    static final int PX = 168;
    private static final int SMALL_VIEW_MAX = 44;
    private static final int LARGE_VIEW_MAX = 84;

    /** Which decoded size a view takes. */
    public enum Tier {
        SMALL(SMALL_VIEW_MAX), LARGE(LARGE_VIEW_MAX);

        private final int largestView;

        Tier(int largestView) {
            this.largestView = largestView;
        }

        /** The tier for a view {@code px} wide (up to 44 is SMALL). */
        public static Tier forSize(double px) {
            return px <= SMALL_VIEW_MAX ? SMALL : LARGE;
        }
    }

    /** Decoded pixels for a tier on a screen of this scale: its largest view times the scale, rounded up. */
    static int decodePx(Tier tier, double scale) {
        return (int) Math.ceil(tier.largestView * Math.max(1, scale));
    }

    private static final Map<String, Image> SMALL_CACHE = Collections.synchronizedMap(BoundedMap.byUse(500));
    private static final Map<String, Image> LARGE_CACHE = Collections.synchronizedMap(BoundedMap.byUse(60));

    private static Map<String, Image> cache(Tier tier) {
        return tier == Tier.SMALL ? SMALL_CACHE : LARGE_CACHE;
    }

    private static final Map<String, CompletableFuture<Image>> LOADING = new ConcurrentHashMap<>();
    /** URLs that failed (e.g. bridge-bot avatars on remote servers a "localhost" Synapse can't fetch). */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final long RETRY_AFTER_MS = 10 * 60 * 1000;

    /** That tier's image only, or null. */
    public static @Nullable Image get(String url, Tier tier) {
        return cache(tier).get(url);
    }

    /** Whatever is decoded for {@code url} (the large one if there, else the small): something to show at once. */
    public static @Nullable Image any(String url) {
        Image large = get(url, Tier.LARGE);
        return large != null ? large : get(url, Tier.SMALL);
    }

    /**
     * The photo for {@code url}: cached, already loading, or a new (single) download + decode off the FX thread.
     * Completes with null when it can't be loaded (then the initials stay; retried after a while, not per click).
     */
    public static CompletableFuture<Image> load(String url, MediaSource svc, Tier tier) {
        return load(url, svc, tier, AnimatedImage.DECODER);
    }

    /** {@code decoder}: where the picture is decoded (a test decodes on its own thread). */
    static CompletableFuture<Image> load(String url, MediaSource svc, Tier tier, java.util.concurrent.Executor decoder) {
        Image cached = get(url, tier);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        Long failedAt = FAILED.get(url);
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return CompletableFuture.completedFuture(null);
        // Registered first and started after: a load finished at once used to remove itself inside computeIfAbsent,
        // which the map refuses ("Recursive update"), leaving a failed load there for good (ImageCacheUiTest).
        String key = url + "|" + tier;
        CompletableFuture<Image> mine = new CompletableFuture<>();
        CompletableFuture<Image> running = LOADING.putIfAbsent(key, mine);
        if (running != null) return running;
        int px = decodePx(tier, ScreenScale.max());
        var _ = svc.media(url, PX, PX).thenApplyAsync(path -> {
            // Decoded completely here, so a view never shows a half-loaded (blank) image.
            // Animated avatars (Discord) stay still: JavaFX's own GIF animation never lets the image go.
            Image img;
            try {
                img = AnimatedImage.still(path, px, px); // a video sticker's first frame too
            } catch (java.io.IOException e) {
                img = null;
            }
            if (img == null || img.isError()) {
                FAILED.put(url, System.currentTimeMillis());
                return (Image) null;
            }
            cache(tier).put(url, img);
            FAILED.remove(url);
            return img;
        }, decoder).exceptionally(err -> {
            FAILED.put(url, System.currentTimeMillis());
            return null;
        }).whenComplete((img, err) -> {
            LOADING.remove(key, mine);
            mine.complete(img);
        });
        return mine;
    }

    static int size(Tier tier) {
        return cache(tier).size();
    }

    static void clear() {
        SMALL_CACHE.clear();
        LARGE_CACHE.clear();
        FAILED.clear();
    }
}

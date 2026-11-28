package app.unichat.ui.media;

import app.unichat.support.BoundedMap;

import javafx.scene.image.Image;
import org.jspecify.annotations.Nullable;

import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.Map;

/**
 * Pictures already decoded, by address and size: a row built again (the timeline rebuilds its bubbles when its width
 * changes) shows its photo at once instead of blank until decoded again, which made every photo blink. The last few
 * hundred, and only while memory allows (soft references).
 */
final class Stills {
    private static final int MAX = 300;
    private static final Map<String, SoftReference<Image>> CACHE = Collections.synchronizedMap(BoundedMap.byUse(MAX));

    private Stills() {}

    /** The picture of {@code url} decoded at that size, or null. */
    static @Nullable Image get(@Nullable String url, int w, int h) {
        SoftReference<Image> r = CACHE.get(key(url, w, h));
        return r == null ? null : r.get();
    }

    static void put(@Nullable String url, int w, int h, @Nullable Image img) {
        if (url != null && img != null && !img.isError()) CACHE.put(key(url, w, h), new SoftReference<>(img));
    }

    /** All let go (the window let go while closed). */
    static void forget() {
        CACHE.clear();
    }

    private static String key(@Nullable String url, int w, int h) {
        return url + "|" + w + "x" + h;
    }
}

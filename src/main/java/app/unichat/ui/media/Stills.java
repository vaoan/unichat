package app.unichat.ui.media;

import javafx.scene.image.Image;

import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pictures already decoded, by address and size: a row built again (the timeline rebuilds its bubbles when its width
 * changes) shows its photo at once instead of blank until decoded again, which made every photo blink. The last few
 * hundred, and only while memory allows (soft references).
 */
final class Stills {
    private static final int MAX = 300;
    private static final Map<String, SoftReference<Image>> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, SoftReference<Image>> eldest) {
                    return size() > MAX;
                }
            });

    private Stills() {}

    /** The picture of {@code url} decoded at that size, or null. */
    static Image get(String url, int w, int h) {
        SoftReference<Image> r = CACHE.get(key(url, w, h));
        return r == null ? null : r.get();
    }

    static void put(String url, int w, int h, Image img) {
        if (url != null && img != null && !img.isError()) CACHE.put(key(url, w, h), new SoftReference<>(img));
    }

    private static String key(String url, int w, int h) {
        return url + "|" + w + "x" + h;
    }
}

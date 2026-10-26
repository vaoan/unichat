package app.unichat.ui;

import javafx.scene.image.Image;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fully decoded avatars/thumbnails by URL and pixel size. List cells are recycled on every refresh, so without
 * this each refresh hid the photo, showed the initials and reloaded it: the avatars flashed.
 */
final class ImageCache {
    private ImageCache() {}

    private static final int MAX = 800;
    private static final Map<String, Image> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> e) {
            return size() > MAX;
        }
    });

    static String key(String url, int px) {
        return px + "|" + url;
    }

    static Image get(String url, int px) {
        return CACHE.get(key(url, px));
    }

    /** Only complete, error-free images are cached (decoded synchronously off the FX thread). */
    static void put(String url, int px, Image img) {
        if (img != null && !img.isError() && img.getProgress() >= 1.0) CACHE.put(key(url, px), img);
    }

    static int size() {
        return CACHE.size();
    }

    static void clear() {
        CACHE.clear();
    }
}

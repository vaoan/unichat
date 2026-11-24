package app.unichat.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A map that keeps its newest {@code max} entries and forgets the rest as more come: what was fetched, sent or heard
 * lately, remembered without growing for good. Not thread-safe, as {@link LinkedHashMap}.
 */
public final class BoundedMap<K, V> extends LinkedHashMap<K, V> {
    private static final long serialVersionUID = 1L;
    private final int max;

    private BoundedMap(int max, boolean byUse) {
        super(16, 0.75f, byUse);
        this.max = max;
    }

    /** Forgets the one used longest ago (read or put). */
    public static <K, V> Map<K, V> byUse(int max) {
        return new BoundedMap<>(max, true);
    }

    /** Forgets the one put first. */
    public static <K, V> Map<K, V> byAge(int max) {
        return new BoundedMap<>(max, false);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { // lesson-ok: bounded-map (the one place)
        return size() > max;
    }
}

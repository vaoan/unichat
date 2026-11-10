package app.unichat.matrix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Size limit of the downloaded-media folder ({@code cache/media}): media is fetched on demand, so anything can be
 * downloaded again. When the folder is over the limit, big files go first, the ones seen longest ago before the others
 * ({@link #touch} on every cache hit keeps what's on screen): a thousand stickers and emoji weigh less than a few long GIFs,
 * and they're what gets shown again and again. Pinned files (saved stickers and GIFs) never go.
 */
final class MediaCache {
    /** Above this a file is "big", and goes before any smaller one. */
    static final long SMALL_BYTES = 1024 * 1024;
    private static final int HASH_LENGTH = 64;
    /** A download's temporary file untouched this long is the leftover of one that never finished. */
    static final long LEFTOVER_MILLIS = 3_600_000L;

    private MediaCache() {}

    /** Marks a cached file as just used. */
    static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // Only affects which file is dropped first.
        }
    }

    /** Deletes least recently used files until the folder holds at most {@code maxBytes}. Returns bytes freed. */
    static long trim(Path dir, long maxBytes) {
        return trim(dir, maxBytes, Set.of());
    }

    /** @param pinned hashes (the start of a cached file's name) of what must stay; it still counts towards the limit */
    static long trim(Path dir, long maxBytes, Set<String> pinned) {
        record Entry(Path path, long size, long used) {}
        if (!Files.isDirectory(dir)) return 0;
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        long leftovers = 0;
        long staleBefore = System.currentTimeMillis() - LEFTOVER_MILLIS;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String n = p.getFileName().toString();
                if (n.endsWith(".download") || n.contains(".part")) {
                    leftovers += deleteIfLeftover(p, staleBefore);
                    continue;
                }
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                if (!a.isRegularFile()) continue;
                total += a.size();
                if (n.length() < HASH_LENGTH || !pinned.contains(n.substring(0, HASH_LENGTH))) {
                    entries.add(new Entry(p, a.size(), a.lastModifiedTime().toMillis()));
                }
            }
        } catch (IOException e) {
            return leftovers;
        }
        if (total <= maxBytes) return leftovers;
        entries.sort(Comparator.comparing((Entry e) -> e.size() <= SMALL_BYTES).thenComparingLong(Entry::used));
        long freed = 0;
        for (Entry e : entries) {
            if (total - freed <= maxBytes) break;
            try {
                if (Files.deleteIfExists(e.path())) freed += e.size();
            } catch (IOException ignored) {
                // Open elsewhere (Windows): next time.
            }
        }
        return freed + leftovers;
    }

    /**
     * A download in progress, or the leftover of one cut short (the app quit or crashed mid-way): deleted once untouched
     * since {@code staleBefore}. Returns the bytes freed.
     */
    private static long deleteIfLeftover(Path p, long staleBefore) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            return a.lastModifiedTime().toMillis() < staleBefore && Files.deleteIfExists(p) ? a.size() : 0;
        } catch (IOException finishedOrOpen) {
            // Moved into place meanwhile, or open elsewhere (Windows): next time.
            return 0;
        }
    }
}

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
 * Size limit of the downloaded-media folder ({@code cache/media}), and the stickers' own ({@code cache/sticker-cache},
 * {@link #trimOldest}): media is fetched on demand, so anything can be downloaded again. When the folder is over the limit, big files go first, the ones seen longest ago before the others
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
        Listing l = list(dir, pinned);
        if (l == null) return 0;
        // Big files first, then the ones seen longest ago.
        l.entries().sort(Comparator.comparing((Entry e) -> e.size() <= SMALL_BYTES).thenComparingLong(Entry::used));
        return drop(l, maxBytes, Integer.MAX_VALUE);
    }

    /**
     * The stickers' room: what was seen longest ago goes first, whatever its size, until at most {@code maxFiles} files
     * and {@code maxBytes} remain. Pinned files stay and count. Returns bytes freed.
     */
    static long trimOldest(Path dir, long maxBytes, int maxFiles, Set<String> pinned) {
        Listing l = list(dir, pinned);
        if (l == null) return 0;
        l.entries().sort(Comparator.comparingLong(Entry::used));
        return drop(l, maxBytes, maxFiles);
    }

    private record Entry(Path path, long size, long used) {}

    /** What a folder holds: the files that may go, and the totals (pinned ones counted); null if it can't be read. */
    private record Listing(List<Entry> entries, long total, int count, long leftovers) {}

    private static Listing list(Path dir, Set<String> pinned) {
        if (!Files.isDirectory(dir)) return null;
        List<Entry> entries = new ArrayList<>();
        long total = 0, leftovers = 0;
        int count = 0;
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
                count++;
                if (n.length() < HASH_LENGTH || !pinned.contains(n.substring(0, HASH_LENGTH))) {
                    entries.add(new Entry(p, a.size(), a.lastModifiedTime().toMillis()));
                }
            }
        } catch (IOException e) {
            return new Listing(List.of(), 0, 0, leftovers);
        }
        return new Listing(entries, total, count, leftovers);
    }

    /** Deletes {@code l}'s entries in their order until the folder is within both limits. Returns bytes freed. */
    private static long drop(Listing l, long maxBytes, int maxFiles) {
        long freed = 0;
        int count = l.count();
        for (Entry e : l.entries()) {
            if (l.total() - freed <= maxBytes && count <= maxFiles) break;
            try {
                if (Files.deleteIfExists(e.path())) {
                    freed += e.size();
                    count--;
                }
            } catch (IOException ignored) {
                // Open elsewhere (Windows): next time.
            }
        }
        return freed + l.leftovers();
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

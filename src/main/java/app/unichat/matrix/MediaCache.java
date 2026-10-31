package app.unichat.matrix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Size limit of the downloaded-media folder ({@code cache/media}): media is fetched on demand, so anything can be
 * downloaded again. When the folder is over the limit, the files seen longest ago go first ({@link #touch} on every
 * cache hit keeps what's on screen).
 */
final class MediaCache {
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
        record Entry(Path path, long size, long used) {}
        if (!Files.isDirectory(dir)) return 0;
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String n = p.getFileName().toString();
                // Downloads in progress.
                if (n.endsWith(".download") || n.contains(".part")) continue;
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                if (!a.isRegularFile()) continue;
                entries.add(new Entry(p, a.size(), a.lastModifiedTime().toMillis()));
                total += a.size();
            }
        } catch (IOException e) {
            return 0;
        }
        if (total <= maxBytes) return 0;
        entries.sort(Comparator.comparingLong(Entry::used));
        long freed = 0;
        for (Entry e : entries) {
            if (total - freed <= maxBytes) break;
            try {
                if (Files.deleteIfExists(e.path())) freed += e.size();
            } catch (IOException ignored) {
                // Open elsewhere (Windows): next time.
            }
        }
        return freed;
    }
}

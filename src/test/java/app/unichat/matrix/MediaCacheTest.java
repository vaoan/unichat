package app.unichat.matrix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaCacheTest {
    private static Path file(Path dir, String name, int size, long usedMillis) throws Exception {
        Path p = Files.write(dir.resolve(name), new byte[size]);
        Files.setLastModifiedTime(p, FileTime.fromMillis(usedMillis));
        return p;
    }

    @Test
    void dropsTheLeastRecentlyUsedUntilUnderTheLimit(@TempDir Path dir) throws Exception {
        Path old = file(dir, "a.jpg", 400, 1_000);
        Path mid = file(dir, "b.jpg", 400, 2_000);
        Path recent = file(dir, "c.jpg", 400, 3_000);

        assertEquals(400, MediaCache.trim(dir, 900));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(mid));
        assertTrue(Files.exists(recent));
    }

    @Test
    void aTouchedFileIsKept(@TempDir Path dir) throws Exception {
        Path seen = file(dir, "a.jpg", 400, 1_000);
        Path other = file(dir, "b.jpg", 400, 2_000);
        MediaCache.touch(seen);

        MediaCache.trim(dir, 500);
        assertTrue(Files.exists(seen));
        assertFalse(Files.exists(other));
    }

    @Test
    void downloadsInProgressAreLeftAlone(@TempDir Path dir) throws Exception {
        Path partial = file(dir, "a.jpg.part12", 1_000, 1_000);
        Path downloading = file(dir, "b.download", 1_000, 1_000);

        assertEquals(0, MediaCache.trim(dir, 0));
        assertTrue(Files.exists(partial));
        assertTrue(Files.exists(downloading));
    }
}

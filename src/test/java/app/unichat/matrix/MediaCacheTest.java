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
        long now = System.currentTimeMillis();
        Path partial = file(dir, "a.jpg.part12", 1_000, now);
        Path downloading = file(dir, "b.download", 1_000, now);

        assertEquals(0, MediaCache.trim(dir, 0));
        assertTrue(Files.exists(partial));
        assertTrue(Files.exists(downloading));
    }

    @Test
    void leftoversOfDownloadsCutShortGoAfterAnHour(@TempDir Path dir) throws Exception {
        long twoHoursAgo = System.currentTimeMillis() - 2 * 3_600_000L;
        Path partial = file(dir, "a.jpg.part12", 1_000, twoHoursAgo);
        Path downloading = file(dir, "b.download", 1_000, twoHoursAgo);
        Path kept = file(dir, "c.jpg", 1_000, twoHoursAgo);

        MediaCache.trim(dir, 1_000_000);

        assertFalse(Files.exists(partial), "an interrupted download's .part is never finished");
        assertFalse(Files.exists(downloading));
        assertTrue(Files.exists(kept), "under the limit, real files stay");
    }

    @Test
    void bigFilesGoBeforeSmallOnesHoweverRecentlySeen(@TempDir Path dir) throws Exception {
        Path sticker = file(dir, "sticker.webp", 50_000, 1_000);
        Path longGif = file(dir, "largo.gif", (int) MediaCache.SMALL_BYTES + 1, 9_000);

        MediaCache.trim(dir, 100_000);

        assertTrue(Files.exists(sticker), "seen long ago, but small");
        assertFalse(Files.exists(longGif));
    }

    @Test
    void pinnedFilesStayEvenOverTheLimit(@TempDir Path dir) throws Exception {
        String hash = "a".repeat(64);
        Path saved = file(dir, hash + ".gif", (int) MediaCache.SMALL_BYTES * 2, 1_000);
        Path other = file(dir, "b".repeat(64) + ".gif", (int) MediaCache.SMALL_BYTES * 2, 9_000);

        MediaCache.trim(dir, 0, java.util.Set.of(hash));

        assertTrue(Files.exists(saved));
        assertFalse(Files.exists(other));
    }

    @Test
    void onlyWhatCanBeScaledHasOneFilePerSize() {
        String direct = "mxc://media-whatsapp.localhost/abc";
        assertEquals(MediaDownloader.cacheKey(direct, 0, 0), MediaDownloader.cacheKey(direct, 152, 152));
        assertEquals(MediaDownloader.cacheKey("https://x/y.gif", 0, 0), MediaDownloader.cacheKey("https://x/y.gif", 200, 200));
        assertFalse(MediaDownloader.cacheKey("mxc://local/abc", 0, 0).equals(MediaDownloader.cacheKey("mxc://local/abc", 152, 152)));
    }
}

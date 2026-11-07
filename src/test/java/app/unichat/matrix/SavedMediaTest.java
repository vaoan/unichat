package app.unichat.matrix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedMediaTest {
    @TempDir Path temp;
    private final Map<String, Path> available = new HashMap<>();
    private final List<String> fetched = new ArrayList<>();

    private SavedMedia saved(long retrySeconds) {
        return new SavedMedia(temp.resolve("saved"), url -> {
            fetched.add(url);
            Path file = available.get(url);
            return file == null ? CompletableFuture.failedFuture(new java.io.IOException("the bridge lost it"))
                    : CompletableFuture.completedFuture(file);
        }, url -> "h" + url, Runnable::run, retrySeconds);
    }

    private Path file(String name, String content) throws Exception {
        return Files.writeString(temp.resolve(name), content);
    }

    @Test
    void itKeepsItsOwnCopyEvenIfTheCacheLosesTheFile() throws Exception {
        Path cached = file("cacheada.webp", "sticker");
        available.put("a", cached);
        SavedMedia s = saved(60);
        s.keep(Set.of("a"));
        Files.delete(cached);
        Path copy = s.find("a");
        assertNotNull(copy);
        assertEquals("ha.webp", copy.getFileName().toString());
        assertEquals("sticker", Files.readString(copy));
    }

    @Test
    void whatsAlreadyCopiedIsNotFetchedAgainAfterARestart() throws Exception {
        available.put("a", file("a.png", "x"));
        saved(60).keep(Set.of("a"));
        fetched.clear();
        saved(60).keep(Set.of("a"));
        assertEquals(List.of(), fetched);
    }

    @Test
    void aFavouriteTakenOutLosesItsCopy() throws Exception {
        available.put("a", file("a.png", "x"));
        available.put("b", file("b.png", "y"));
        SavedMedia s = saved(60);
        s.keep(Set.of("a", "b"));
        s.keep(Set.of("b"));
        assertNull(s.find("a"));
        assertNotNull(s.find("b"));
    }

    @Test
    void aListThatStartsEmptyDeletesNothing() throws Exception {
        available.put("a", file("a.png", "x"));
        saved(60).keep(Set.of("a"));
        SavedMedia afterDamage = saved(60);
        afterDamage.keep(Set.of());
        assertNotNull(afterDamage.find("a"), "a damaged list isn't the person removing them");
    }

    @Test
    void whatCouldNotBeFetchedIsTriedAgain() throws Exception {
        SavedMedia s = saved(0);
        available.put("b", file("b.png", "y"));
        Path later = file("a.png", "x");
        s.keep(Set.of("a", "b"));
        assertNotNull(s.find("b"), "one that fails doesn't stop the others");
        available.put("a", later);
        long end = System.currentTimeMillis() + 5000;
        while (s.find("a") == null && System.currentTimeMillis() < end) Thread.sleep(20);
        assertNotNull(s.find("a"));
        assertTrue(fetched.stream().filter("a"::equals).count() >= 2);
    }

    @Test
    void theDownloaderPrefersTheCopyAndAsksNobody() throws Exception {
        String url = "mxc://media-telegram.localhost/perdido";
        Path dir = app.unichat.settings.Settings.dataDir().resolve("media-library");
        Files.createDirectories(dir);
        Path copy = Files.writeString(dir.resolve(app.unichat.support.Hashes.sha256(MediaDownloader.cacheKey(url, 0, 0)) + ".webp"), "x");
        try (var io = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertEquals(copy, new MediaDownloader(null, io).media(url, 200, 200).get());
        } finally {
            Files.delete(copy);
        }
    }
}

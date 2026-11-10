package app.unichat.autosave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reel the bridge gave only as its picture and a link opened Instagram: its video is fetched by the engine that
 * saves links and played in Unichat. Fetched once into the media cache; the engine's own folder never stays behind.
 */
class LinkVideoTest {
    private static final String REEL = "https://www.instagram.com/reel/DZgKharlUlu/?id=3918177952668";

    @TempDir Path cache;

    @Test
    void theVideoIsFetchedOnceAndKeptInTheMediaCache() throws Exception {
        AtomicInteger fetched = new AtomicInteger();
        LinkVideo.Fetch engine = (url, dir) -> {
            fetched.incrementAndGet();
            Files.writeString(dir.resolve("reel [tag].mp4"), "video");
            Files.writeString(dir.resolve(".reel [tag].mp4.part"), "half"); // what the engine leaves while it works
            return null;
        };
        Path video = LinkVideo.fetch(REEL, cache, engine);
        assertEquals(cache, video.getParent(), "a file of the media cache, trimmed with the rest");
        assertTrue(video.getFileName().toString().endsWith(".mp4"));
        assertEquals("video", Files.readString(video));
        assertEquals(video, LinkVideo.fetch(REEL, cache, engine), "the second time it's there already");
        assertEquals(1, fetched.get());
        try (var left = Files.list(cache)) {
            assertEquals(1, left.count(), "only the video: the engine's folder is gone");
        }
    }

    @Test
    void whyItFailedIsTheErrorAndNothingStays() throws Exception {
        LinkVideo.Fetch refused = (url, dir) -> Failure.forGood("Instagram pide iniciar sesión");
        IOException e = assertThrows(IOException.class, () -> LinkVideo.fetch(REEL, cache, refused));
        assertEquals("Instagram pide iniciar sesión", e.getMessage());
        LinkVideo.Fetch nothing = (url, dir) -> null; // "done", yet no video
        assertThrows(IOException.class, () -> LinkVideo.fetch(REEL, cache, nothing));
        try (var left = Files.list(cache)) {
            assertFalse(left.findAny().isPresent());
        }
    }
}

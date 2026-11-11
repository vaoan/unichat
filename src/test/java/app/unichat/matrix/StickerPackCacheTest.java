package app.unichat.matrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A set is asked for once a week at most; an older copy still serves when the bridge can't answer. */
class StickerPackCacheTest {
    @TempDir Path dir;

    @Test
    void aSetIsKeptAndGoesStaleAfterAWeek() throws Exception {
        StickerPackCache cache = new StickerPackCache(dir);
        String url = "https://t.me/addstickers/gatitos";
        assertNull(cache.fresh(url));
        cache.put(url, new ObjectMapper().readTree("{\"images\":{}}"));
        assertEquals("{\"images\":{}}", cache.fresh(url).toString());
        Path f = dir.resolve("gatitos.json");
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(StickerPackCache.FRESH).minusSeconds(60)));
        assertNull(cache.fresh(url), "stale: ask the bridge again");
        assertEquals("{\"images\":{}}", cache.any(url).toString(), "but it serves when the bridge can't");
        assertNull(cache.any("https://t.me/addstickers/otro"));
    }
}

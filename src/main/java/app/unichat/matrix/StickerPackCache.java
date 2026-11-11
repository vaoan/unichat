package app.unichat.matrix;

import app.unichat.model.StickerPack;
import app.unichat.support.SafeFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Sticker sets as their bridge gave them ({@code cache/sticker-packs/<short name>.json}): a set is fetched from
 * Telegram sticker by sticker, so it's asked for again only after {@link #FRESH}.
 */
final class StickerPackCache {
    static final Duration FRESH = Duration.ofDays(7);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;

    StickerPackCache(Path dir) {
        this.dir = dir;
    }

    /** The copy kept, if it's recent enough not to ask again. */
    JsonNode fresh(String url) {
        Path f = file(url);
        try {
            if (f == null || !Files.isRegularFile(f)) return null;
            if (Files.getLastModifiedTime(f).toInstant().isBefore(Instant.now().minus(FRESH))) return null;
        } catch (IOException e) {
            return null;
        }
        return any(url);
    }

    /** The copy kept, however old; null when none or unreadable. */
    JsonNode any(String url) {
        Path f = file(url);
        try {
            return f == null || !Files.isRegularFile(f) ? null : JSON.readTree(f.toFile());
        } catch (IOException e) {
            System.err.println("Sticker set not read from the cache: " + e.getClass().getSimpleName());
            return null;
        }
    }

    void put(String url, JsonNode content) {
        Path f = file(url);
        if (f == null) return;
        try {
            Files.createDirectories(dir);
            SafeFile.replace(f, content.toString().getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException e) {
            System.err.println("Sticker set not cached: " + e.getClass().getSimpleName());
        }
    }

    /** The set's short name names its file: {@link StickerPack#isLink} lets only letters, digits and underscores through. */
    private Path file(String url) {
        return StickerPack.isLink(url) ? dir.resolve(url.substring(url.lastIndexOf('/') + 1) + ".json") : null;
    }
}

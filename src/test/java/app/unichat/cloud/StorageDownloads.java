package app.unichat.cloud;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What every {@link Storage} must do when a file comes down into a file: the same checks for each service. */
final class StorageDownloads {
    private StorageDownloads() {}

    /** {@code path} (holding {@code expected}) comes down whole over an older copy, and one that isn't there changes nothing. */
    static void check(Storage storage, String path, byte[] expected, Path dir) throws Exception {
        Path target = Files.writeString(dir.resolve("video.mp4"), "una copia cortada");
        assertTrue(storage.get(path, target));
        assertArrayEquals(expected, Files.readAllBytes(target), "whole, in place of the older copy");

        Path absent = dir.resolve("nada.mp4");
        assertFalse(storage.get("Unichat/nada.mp4", absent), "not there");
        assertFalse(Files.exists(absent), "and nothing written");
        try (var left = Files.list(dir)) {
            assertEquals(List.of("video.mp4"), left.map(p -> p.getFileName().toString()).toList(), "no .part left behind");
        }
    }
}

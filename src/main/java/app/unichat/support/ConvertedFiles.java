package app.unichat.support;

import java.nio.file.Path;

/**
 * A conversion lives beside its original in the media cache, so the cache's limit covers it and it's made once. While
 * it's being written its name has {@code .part} added, which the cache leaves alone.
 */
public final class ConvertedFiles {
    private ConvertedFiles() {}

    public static Path beside(Path original, String extension) {
        return original.resolveSibling("converted-" + Hashes.sha256(PathParts.name(original)) + "." + extension);
    }

    /** Whether the conversion is already there; if so it counts as just seen, for the cache. */
    public static boolean reuse(Path converted) {
        if (!java.nio.file.Files.isRegularFile(converted)) return false;
        try {
            java.nio.file.Files.setLastModifiedTime(converted, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (java.io.IOException e) {
            // still there: only the cache's order is off
        }
        return true;
    }
}

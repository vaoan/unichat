package app.unichat.login;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;

/**
 * Reading a browser's cookie database from a copy: the browser keeps the original locked while it runs, and recent
 * cookies may still sit in its write-ahead log ({@code <name>-wal}), copied beside it.
 */
final class CookieDbCopy {
    private CookieDbCopy() {}

    /** What {@code reader} makes of a copy of {@code db}; null when it couldn't be copied or read. The copy is deleted. */
    static <T> T read(Path db, Function<Path, T> reader) {
        Path tmp = null;
        Path wal = null;
        try {
            tmp = Files.createTempFile("unichat-cookies-", ".sqlite");
            tmp.toFile().deleteOnExit(); // fallback if the delete in finally fails (e.g. locked on Windows)
            Files.copy(db, tmp, StandardCopyOption.REPLACE_EXISTING);
            Path srcWal = walOf(db);
            if (Files.isRegularFile(srcWal)) {
                wal = walOf(tmp);
                wal.toFile().deleteOnExit();
                Files.copy(srcWal, wal, StandardCopyOption.REPLACE_EXISTING);
            }
            return reader.apply(tmp);
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            delete(tmp, wal);
        }
    }

    /** SQLite's write-ahead log beside a database: Chromium's {@code Cookies-wal}, Firefox's {@code cookies.sqlite-wal}. */
    static Path walOf(Path db) {
        return db.resolveSibling(db.getFileName() + "-wal");
    }

    /** Removes the copy and the files SQLite left beside it. */
    private static void delete(Path tmp, Path wal) {
        deleteQuietly(wal);
        if (tmp != null) {
            deleteQuietly(tmp);
            deleteQuietly(tmp.resolveSibling(tmp.getFileName() + "-shm"));
            deleteQuietly(tmp.resolveSibling(tmp.getFileName() + "-journal"));
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }
}

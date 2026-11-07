package app.unichat.support;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Deleting whole folders. A link inside is removed, never followed. */
public final class Folders {
    private Folders() {}

    /** Removes {@code dir} and all it holds; nothing there is fine. Whatever can go goes, then the first failure is thrown. */
    public static void delete(Path dir) throws IOException {
        List<IOException> failures = sweep(dir);
        if (!failures.isEmpty()) throw failures.get(0);
    }

    /** Removes as much of {@code dir} as it can: a file that resists doesn't save the ones after it. Null is fine. */
    public static void deleteQuietly(Path dir) {
        if (dir != null) sweep(dir);
    }

    private static List<IOException> sweep(Path dir) {
        List<IOException> failures = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    remove(f, failures);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                    if (!(e instanceof NoSuchFileException)) failures.add(e);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult postVisitDirectory(Path d, IOException e) {
                    if (e != null) failures.add(e);
                    remove(d, failures);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            failures.add(e);
        }
        return failures;
    }

    private static void remove(Path p, List<IOException> failures) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            failures.add(e);
        }
    }
}

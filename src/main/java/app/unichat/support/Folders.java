package app.unichat.support;

import org.jspecify.annotations.Nullable;

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

    /**
     * Removes what's in {@code root} (files and folders) last changed longer than {@code keep} ago: a folder of things
     * made for a while (to send, to copy) that mustn't stay for good. No folder is fine.
     */
    public static void deleteOlderThan(Path root, java.time.Duration keep) {
        if (!Files.isDirectory(root)) return;
        long before = System.currentTimeMillis() - keep.toMillis();
        try (java.nio.file.DirectoryStream<Path> items = Files.newDirectoryStream(root)) {
            for (Path d : items) if (Files.getLastModifiedTime(d).toMillis() < before) deleteQuietly(d);
        } catch (IOException e) {
            // next time
        }
    }

    /**
     * {@code dir}, made if missing, readable by its owner alone where the system has owners and modes (another account on
     * the computer can't list or read it): for what's private, as a copy of what chats said.
     */
    public static Path ownerOnly(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        return dir;
    }

    /** Removes {@code dir} and all it holds; nothing there is fine. Whatever can go goes, then the first failure is thrown. */
    public static void delete(Path dir) throws IOException {
        List<IOException> failures = sweep(dir);
        if (!failures.isEmpty()) throw failures.get(0);
    }

    /** Removes as much of {@code dir} as it can: a file that resists doesn't save the ones after it. Null is fine. */
    public static void deleteQuietly(@Nullable Path dir) {
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

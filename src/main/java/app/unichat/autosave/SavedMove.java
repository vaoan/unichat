package app.unichat.autosave;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Takes what a chat saved to its new folder when the folder changes: otherwise the new one starts empty and the files
 * look lost in the old one. File by file, folders merged, nothing overwritten ({@link FileNames#free}); the old folder
 * goes only once empty. Hidden files (Finder's .DS_Store) stay behind.
 */
final class SavedMove {
    private SavedMove() {}

    /** How many files went from {@code from} to {@code to}; nothing when one is inside the other or there's nothing. */
    static int move(Path from, Path to) throws IOException {
        Path a = from.toAbsolutePath().normalize(), b = to.toAbsolutePath().normalize();
        if (!Files.isDirectory(a) || a.startsWith(b) || b.startsWith(a)) return 0;
        Files.createDirectories(b);
        int moved = 0;
        try (DirectoryStream<Path> in = Files.newDirectoryStream(a)) {
            for (Path p : in) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (Files.isDirectory(p)) moved += move(p, b.resolve(name));
                else {
                    Files.move(p, FileNames.free(b, name));
                    moved++;
                }
            }
        }
        removeIfOnlyHidden(a);
        return moved;
    }

    private static void removeIfOnlyHidden(Path dir) throws IOException {
        try (DirectoryStream<Path> in = Files.newDirectoryStream(dir)) {
            for (Path p : in) if (!p.getFileName().toString().startsWith(".") || Files.isDirectory(p)) return;
        }
        try (DirectoryStream<Path> in = Files.newDirectoryStream(dir)) {
            for (Path p : in) Files.delete(p);
        }
        Files.delete(dir);
    }
}

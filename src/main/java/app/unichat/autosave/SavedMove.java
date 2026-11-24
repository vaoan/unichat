package app.unichat.autosave;

import app.unichat.model.Chat;
import app.unichat.support.PathParts;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Takes what a chat saved to its new folder when the folder changes: otherwise the new one starts empty and the files
 * look lost in the old one. File by file, folders merged, nothing overwritten ({@link FileNames#free}); the old folder
 * goes only once empty. Hidden files (Finder's .DS_Store) stay behind.
 */
final class SavedMove {
    private SavedMove() {}

    /**
     * A new base folder (null: the default), taking along each chat's folder under the old one (never the base whole:
     * it can hold the person's own things). Gives how many files moved.
     */
    static int toBase(List<Chat> chats, @Nullable Path folder) throws IOException {
        Path before = SaveFolders.base();
        SaveFolders.setBase(folder);
        Path after = SaveFolders.base();
        int moved = 0;
        for (Chat c : chats) {
            if (SaveFolders.hasOwn(c.id())) continue;
            String name = SaveFolders.name(c);
            moved += move(before.resolve(name), after.resolve(name));
        }
        return moved;
    }

    /** A chat's own folder (null: under the base again), taking along what it saved where it was. */
    static int toOwn(Chat chat, @Nullable Path folder) throws IOException {
        Path before = SaveFolders.of(chat);
        SaveFolders.setOwn(chat.id(), folder);
        return move(before, SaveFolders.of(chat));
    }

    /** How many files went from {@code from} to {@code to}; nothing when one is inside the other or there's nothing. */
    static int move(Path from, Path to) throws IOException {
        Path a = from.toAbsolutePath().normalize();
        Path b = to.toAbsolutePath().normalize();
        if (!Files.isDirectory(a) || a.startsWith(b) || b.startsWith(a)) return 0;
        Files.createDirectories(b);
        int moved = 0;
        try (DirectoryStream<Path> in = Files.newDirectoryStream(a)) {
            for (Path p : in) {
                String name = PathParts.name(p);
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
            for (Path p : in) if (!PathParts.name(p).startsWith(".") || Files.isDirectory(p)) return;
        }
        try (DirectoryStream<Path> in = Files.newDirectoryStream(dir)) {
            for (Path p : in) Files.delete(p);
        }
        Files.delete(dir);
    }
}

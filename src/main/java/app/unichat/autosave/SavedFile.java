package app.unichat.autosave;

import app.unichat.model.Message;
import app.unichat.support.MarkOfTheWeb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.function.DoubleConsumer;
import org.jspecify.annotations.Nullable;

/** A message's file written into a chat's folder: what saving as it arrives and exploring the past both do. */
final class SavedFile {
    private SavedFile() {
    }

    /**
     * Copies {@code m}'s file into {@code dir} under {@link FileNames#forSaving}, unless it's there already. Written
     * under a hidden name and renamed once whole: nothing (the cloud, the person) ever takes a half-written file.
     * {@code downloaded[0]}: the cached file, for telling why it failed. False: it was there already.
     */
    static boolean copyInto(AutoSave.Saver saver, Message m, Path dir, @Nullable Path[] downloaded, DoubleConsumer progress)
            throws IOException, InterruptedException {
        if (FileNames.savedIn(dir, m)) return false;
        // Both callers let through only what SaveKind.of names, which a message without an address never is.
        downloaded[0] = saver.media(java.util.Objects.requireNonNull(m.mediaUrl(), "media without an address"), progress);
        Files.createDirectories(dir);
        Path target = FileNames.free(dir, FileNames.forSaving(m, ZoneId.systemDefault()));
        Path part = dir.resolve("." + target.getFileName() + ".part");
        Files.copy(downloaded[0], part, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE); // lesson-ok: atomic-move (never replaces: the name was chosen free)
        MarkOfTheWeb.mark(target);
        return true;
    }
}

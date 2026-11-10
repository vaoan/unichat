package app.unichat.ui;

import app.unichat.autosave.FileNames;
import app.unichat.service.MediaSource;
import app.unichat.model.Message;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.function.Consumer;

/** "Guardar como…" for any received or sent media: photos, videos, audio, files and stickers. */
final class MediaSaver {
    private MediaSaver() {}

    /** A safe file name for {@code m} ({@link FileNames#forMessage}): never a path outside the chosen folder. */
    static String suggestedName(Message m, ZoneId zone) {
        return FileNames.forMessage(m, zone);
    }

    /** Asks where to save, then copies the downloaded file there. */
    static void save(Window owner, MediaSource svc, Message m, Consumer<String> toast) {
        save(owner, svc, m.mediaUrl(), suggestedName(m, ZoneId.systemDefault()), toast);
    }

    /** Saves the picture at {@code url} (an avatar: no message to name it after) under a name the person picks. */
    static void save(Window owner, MediaSource svc, String url, String suggested, Consumer<String> toast) {
        FileChooser fc = new FileChooser();
        fc.setTitle(I18n.t("saver.title"));
        fc.setInitialFileName(suggested);
        File target = fc.showSaveDialog(owner);
        if (target == null) return;
        svc.media(url, 0, 0).whenComplete((p, err) -> {
            String msg;
            try {
                if (err != null) throw err;
                Files.copy(p, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                msg = I18n.t("saver.saved", target.getName());
            } catch (Throwable e) {
                msg = I18n.t("saver.failed");
            }
            String text = msg;
            Platform.runLater(() -> toast.accept(text));
        });
    }
}

package app.unichat.ui.media;

import app.unichat.autosave.FileNames;
import app.unichat.service.MediaSource;
import app.unichat.model.Message;
import app.unichat.support.I18n;
import app.unichat.video.GifVideos;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** "Guardar como…" for any received or sent media: photos, videos, audio, files and stickers. */
public final class MediaSaver {
    private MediaSaver() {}

    /** A safe file name for {@code m} ({@link FileNames#forMessage}): never a path outside the chosen folder. */
    static String suggestedName(Message m, ZoneId zone) {
        return FileNames.forMessage(m, zone);
    }

    /**
     * Asks where to save, then copies the downloaded file there; a GIF that came as a video (Telegram's) as the GIF it
     * was sent as ({@link GifVideos}), or as the video when it can't be made.
     */
    public static void save(Window owner, MediaSource svc, Message m, Consumer<String> toast) {
        String name = suggestedName(m, ZoneId.systemDefault());
        if (GifVideos.is(m)) save(owner, svc, m.mediaUrl(), GifVideos.gifName(name), GifVideos::gifOr, toast);
        else save(owner, svc, m.mediaUrl(), name, p -> p, toast);
    }

    /** Saves the picture at {@code url} (an avatar: no message to name it after) under a name the person picks. */
    public static void save(Window owner, MediaSource svc, String url, String suggested, Consumer<String> toast) {
        save(owner, svc, url, suggested, p -> p, toast);
    }

    /** {@code made}: the file to save from the one downloaded; the name chosen takes its extension if it differs. */
    private static void save(Window owner, MediaSource svc, String url, String suggested, UnaryOperator<Path> made, Consumer<String> toast) {
        FileChooser fc = new FileChooser();
        fc.setTitle(I18n.t("saver.title"));
        fc.setInitialFileName(suggested);
        File target = fc.showSaveDialog(owner);
        if (target == null) return;
        svc.media(url, 0, 0).whenComplete((p, err) -> {
            String msg;
            try {
                if (err != null) throw err;
                Path file = made.apply(p);
                Path to = withExtensionOf(target.toPath(), file);
                Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING);
                msg = I18n.t("saver.saved", to.getFileName().toString());
            } catch (Throwable e) {
                msg = I18n.t("saver.failed");
            }
            String text = msg;
            Platform.runLater(() -> toast.accept(text));
        });
    }

    /** {@code target} with {@code file}'s extension when they differ: a GIF that couldn't be made is saved as its video. */
    static Path withExtensionOf(Path target, Path file) {
        String want = extension(file.getFileName().toString()), has = extension(target.getFileName().toString());
        if (want.equalsIgnoreCase(has)) return target;
        String n = target.getFileName().toString();
        return target.resolveSibling((has.isEmpty() ? n : n.substring(0, n.length() - has.length())) + want);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }
}

package app.unichat.ui;

import app.unichat.service.MediaSource;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.MimeTypes;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** "Guardar como…" for any received or sent media: photos, videos, audio, files and stickers. */
final class MediaSaver {
    private MediaSaver() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * A safe file name: the original one without any folder part (never a path outside the chosen folder), or one
     * made from the kind and the message time ("imagen-20261026-100405.jpg"). Stickers always get the made-up name.
     */
    static String suggestedName(Message m, ZoneId zone) {
        String name = m.fileName() == null ? "" : m.fileName().replace('\\', '/');
        name = windowsSafe(name.substring(name.lastIndexOf('/') + 1).strip());
        String ext = MimeTypes.extension(m.mimeType());
        if (name.isEmpty() || name.equals(".") || name.equals("..") || m.type() == MessageType.STICKER) {
            String kind = switch (m.type()) {
                case IMAGE -> I18n.t("saver.kind.image");
                case VIDEO -> I18n.t("saver.kind.video");
                case AUDIO -> I18n.t("saver.kind.audio");
                case STICKER -> I18n.t("saver.kind.sticker");
                default -> I18n.t("saver.kind.file");
            };
            return kind + "-" + STAMP.format(m.timestamp().atZone(zone)) + ext;
        }
        return name.contains(".") || ext.isEmpty() ? name : name + ext;
    }

    private static final java.util.regex.Pattern RESERVED =
            java.util.regex.Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

    /**
     * Windows reads "C:x" as relative to drive C and "a:b" as a hidden stream, rejects {@code <>"|?*}, control
     * characters, trailing dots/spaces and DOS device names: clean all of those, and keep it under 200 characters.
     */
    private static String windowsSafe(String name) {
        String n = name.replaceAll("[<>:\"|?*\\x00-\\x1F]", "_").replaceAll("[. ]+$", "");
        if (RESERVED.matcher(n).matches()) n = "_" + n;
        if (n.length() > 200) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, 200 - ext.length()) + ext;
        }
        return n;
    }

    /** Asks where to save, then copies the downloaded file there. */
    static void save(Window owner, MediaSource svc, Message m, Consumer<String> toast) {
        FileChooser fc = new FileChooser();
        fc.setTitle(I18n.t("saver.title"));
        fc.setInitialFileName(suggestedName(m, ZoneId.systemDefault()));
        File target = fc.showSaveDialog(owner);
        if (target == null) return;
        svc.media(m.mediaUrl(), 0, 0).whenComplete((p, err) -> {
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

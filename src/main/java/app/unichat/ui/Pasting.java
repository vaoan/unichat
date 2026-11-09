package app.unichat.ui;

import app.unichat.service.SendAs;
import app.unichat.settings.Settings;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * What pasting in the message box sends when it isn't text: files copied in Finder or Explorer, or a picture copied from
 * a web page or an app (which only exists in the clipboard, so it's saved as a PNG first).
 */
final class Pasting {
    /** What was copied: files, plain text and a picture, each possibly missing. */
    record Copied(List<File> files, String text, Image image) {
        static Copied from(Clipboard c) {
            return new Copied(c.hasFiles() ? c.getFiles() : List.of(), c.hasString() ? c.getString() : null,
                    c.hasImage() ? c.getImage() : null);
        }
    }

    /** Files to send, and how. */
    record Send(List<File> files, SendAs as) {}

    private Pasting() {}

    /** Where pictures from the clipboard are saved: with the photos made for sending, deleted after a day. */
    static Path folder() {
        return Settings.dataDir().resolve("cache").resolve("sending").resolve("pasted-" + System.currentTimeMillis());
    }

    /**
     * What to send, or null to paste as text. Files win (Finder also copies their names and icon); text wins over a
     * picture (Office copies a picture of the text along with it).
     */
    static Send toSend(Copied c, Path dir) throws IOException {
        if (c.files() != null && !c.files().isEmpty()) return new Send(List.copyOf(c.files()), SendAs.AS_IS);
        if (c.image() == null || c.image().isError() || (c.text() != null && !c.text().isEmpty())) return null;
        Files.createDirectories(dir);
        Path png = dir.resolve("image-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png");
        if (!ImageIO.write(SwingFXUtils.fromFXImage(c.image(), null), "png", png.toFile())) throw new IOException("no PNG writer");
        return new Send(List.of(png.toFile()), SendAs.PHOTO);
    }
}

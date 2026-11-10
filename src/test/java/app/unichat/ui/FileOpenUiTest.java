package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.MediaSource;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file in a chat opens with the system's app when clicked, through AWT's own thread: from the thread the download
 * finished on, a click did nothing at all on macOS (a PDF in a Telegram chat).
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class FileOpenUiTest {
    @TempDir
    Path dir;

    @Test
    void aFileOpensWithTheSystemsAppThroughAwtsThread() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        Path pdf = Files.writeString(dir.resolve("nutricion.pdf"), "%PDF-1.4");
        // The download finishes on a thread of its own, as a real one does.
        MediaSource source = (url, w, h) -> CompletableFuture.supplyAsync(() -> pdf);
        Path[] opened = {null};
        boolean[] onAwt = {false};
        CountDownLatch done = new CountDownLatch(1);
        Browser.Opener before = Browser.opener;
        Browser.opener = file -> {
            opened[0] = file;
            onAwt[0] = java.awt.EventQueue.isDispatchThread();
            done.countDown();
        };
        try {
            Stage[] stage = new Stage[1];
            Node chip = FxWait.fx(() -> {
                stage[0] = new Stage();
                stage[0].setOpacity(0);
                stage[0].setScene(new Scene(new StackPane(), 400, 200));
                stage[0].show();
                MessageMedia media = new MessageMedia(source, s -> { }, s -> { },
                        new app.unichat.model.MediaLibrary(dir.resolve("media-library.json")), stage[0]::getScene,
                        new AudioPlayback(source, s -> { }));
                Message m = new Message("$f", "!c", "@a:s", "Ana", false, Instant.EPOCH);
                m.setType(MessageType.FILE);
                m.setMediaUrl("mxc://s/nutricion");
                m.setFileName("nutricion.pdf");
                Node n = media.fileNode(m);
                ((StackPane) stage[0].getScene().getRoot()).getChildren().add(n);
                return n;
            });
            FxWait.fx(() -> {
                Event.fireEvent(chip, new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5, MouseButton.PRIMARY, 1,
                        false, false, false, false, true, false, false, true, false, false, null));
                return null;
            });
            done.await();
            assertEquals(pdf, opened[0], "the downloaded file");
            assertTrue(onAwt[0], "opened on AWT's thread, not the download's");
            FxWait.fx(() -> { stage[0].close(); return null; });
        } finally {
            Browser.opener = before;
        }
    }
}

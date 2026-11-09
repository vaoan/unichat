package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ⌘V in the message box: a copied picture is sent, copied text is written. Headless only: it fills the clipboard. */
@EnabledIfSystemProperty(named = "unichat.headless", matches = "true")
class PasteUiTest {
    private static final String CHAT = "!familia:demo";

    @Test
    void aCopiedPictureIsSentAndTextIsWritten() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        int before = svc.messages(CHAT).size();
        Composer composer = FxWait.fx(() -> {
            Composer c = new Composer(svc, x -> { });
            c.setChat(CHAT);
            Stage stage = new Stage();
            stage.setScene(new Scene(c, 700, 160));
            stage.show();
            ClipboardContent picture = new ClipboardContent();
            picture.putImage(new WritableImage(64, 48)); // as Chrome's "Copy image" leaves it: a picture, no text
            Clipboard.getSystemClipboard().setContent(picture);
            c.paste();
            return c;
        });
        FxWait.until("the pasted picture in the chat", () -> svc.messages(CHAT).size() > before);
        Message sent = svc.messages(CHAT).get(svc.messages(CHAT).size() - 1);
        assertEquals(MessageType.IMAGE, sent.type());
        assertTrue(sent.fileName().startsWith("image-") && sent.fileName().endsWith(".png"), sent.fileName());

        int after = svc.messages(CHAT).size();
        String box = FxWait.fx(() -> {
            ClipboardContent text = new ClipboardContent();
            text.putString("hola");
            Clipboard.getSystemClipboard().setContent(text);
            composer.paste();
            return ((TextArea) composer.lookup(".text-area")).getText();
        });
        assertEquals("hola", box);
        assertEquals(after, svc.messages(CHAT).size(), "text isn't sent");
    }
}

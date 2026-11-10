package app.unichat.ui.timeline;

import app.unichat.ui.FxWait;
import app.unichat.model.RoomFeatures;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The message box's right-click menu offers the formats the chat's network shows, and ⌘B makes bold. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class FormatMenuUiTest {
    private static final String CHAT = "!familia:demo";

    private static Menu format(ContextMenu menu) {
        return (Menu) menu.getItems().get(menu.getItems().size() - 1);
    }

    private static List<String> shown(TextArea box) {
        ContextMenu menu = box.getContextMenu();
        menu.getOnShowing().handle(null);
        return format(menu).getItems().stream().map(MenuItem::getText).toList();
    }

    /** ⌘ on a Mac, Ctrl elsewhere. */
    private static KeyEvent shortcut(KeyCode k) {
        boolean mac = app.unichat.support.Os.mac();
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", k, false, !mac, false, mac);
    }

    @Test
    void formatsFollowTheNetworkAndTheShortcutWraps() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        FxWait.fx(() -> {
            Composer c = new Composer(svc, x -> { });
            c.setChat(CHAT);
            Stage stage = new Stage();
            stage.setScene(new Scene(c, 700, 160));
            stage.show();
            TextArea box = (TextArea) c.lookup(".text-area");

            svc.chat(CHAT).setFeatures(RoomFeatures.UNKNOWN);
            assertEquals(8, shown(box).size(), "a chat whose bridge says nothing: all of them");
            assertTrue(shown(box).contains("Negrita"));

            svc.chat(CHAT).setFeatures(RoomFeatures.parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    "{\"formatting\":{\"bold\":2,\"italic\":2,\"strikethrough\":2,\"underline\":-1,\"spoiler\":-1}}")));
            assertEquals(List.of("Negrita", "Cursiva", "Tachado"), shown(box), "WhatsApp-like: no underline or spoiler");

            box.setText("hola mundo");
            box.selectRange(5, 10);
            Event.fireEvent(box, shortcut(KeyCode.B));
            assertEquals("hola *mundo*", box.getText());
            assertEquals("mundo", box.getSelectedText(), "still selected, to add another");
            Event.fireEvent(box, shortcut(KeyCode.U));
            assertEquals("hola *mundo*", box.getText(), "underline isn't offered here: nothing");
            box.undo();
            assertEquals("hola mundo", box.getText(), "⌘Z takes it back");
            stage.close();
            return null;
        });
    }
}

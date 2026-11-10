package app.unichat.ui.chats;

import app.unichat.ui.FxWait;
import app.unichat.model.Chat;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The profile's six actions: low priority and the chat's options among them; leaving isn't one a stray click reaches. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class InfoActionsUiTest {
    @Test
    void sixActionsAndNoLeavingAtHand() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        String id = "!familia:demo";
        svc.setLowPriority(id, false).get(); // the test data folder is the whole run's
        AtomicReference<Chat> opened = new AtomicReference<>();
        InfoPane pane = FxWait.fx(() -> {
            InfoPane p = new InfoPane(svc, () -> { }, t -> { });
            p.setOnOptions(opened::set);
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(p, 360, 900));
            stage.show();
            p.show(svc.chat(id));
            return p;
        });
        FxWait.until("the actions show", () -> !pane.lookupAll(".info-action").isEmpty());
        List<Button> actions = FxWait.fx(() -> pane.lookupAll(".info-action").stream().map(n -> (Button) n).toList());
        assertEquals(6, actions.size());
        List<String> names = FxWait.fx(() -> actions.stream().map(Button::getAccessibleText).toList());
        assertTrue(names.contains("Mover a prioridad baja") && names.contains("Opciones del chat"), names.toString());
        Button low = actions.get(names.indexOf("Mover a prioridad baja"));
        FxWait.fx(() -> { low.fire(); return null; });
        assertTrue(svc.chat(id).lowPriority());
        svc.setLowPriority(id, false).get();
        Button options = actions.get(names.indexOf("Opciones del chat"));
        FxWait.fx(() -> { options.fire(); return null; });
        assertEquals(id, opened.get().id(), "the gear opens this chat's options");
        assertTrue(FxWait.fx(() -> pane.lookupAll(".danger-button").isEmpty()), "leaving is in the options now, not here");
        FxWait.fx(() -> { pane.getScene().getWindow().hide(); return null; });
        svc.stop();
    }
}

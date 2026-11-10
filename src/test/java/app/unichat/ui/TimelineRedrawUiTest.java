package app.unichat.ui;

import app.unichat.ui.desktop.Notifier;
import app.unichat.model.Message;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A new message leaves the rows on screen alone, and what does change in one still shows. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class TimelineRedrawUiTest {
    private static final String CHAT = "!familia:demo";

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }

    private static String texts(Node root) {
        List<String> out = new ArrayList<>();
        root.lookupAll(".msg-text").forEach(n -> out.add(((Text) n).getText()));
        return String.join("", out);
    }

    private static boolean shown(Node n) {
        for (Node p = n; p != null; p = p.getParent()) if (!p.isVisible()) return false;
        return true;
    }

    private static Node bubble(Scene scene, String text) {
        return scene.getRoot().lookupAll(".bubble").stream().filter(n -> shown(n) && texts(n).contains(text)).findFirst().orElse(null);
    }

    @Test
    void onlyWhatChangedIsDrawnAgain() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        svc.skipDemoPauses(); // sent, delivered and read come at once, in order
        svc.sendText(CHAT, "primero de la prueba", null).get();
        // The row just before a new one can change its corners when they join a group: this keeps "primero" away from that.
        svc.sendText(CHAT, "intermedio", null).get();
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            MainView view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, u -> { });
            Scene sc = new Scene(view, 1000, 800);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.openChatId(CHAT);
            return sc;
        });
        FxWait.until("the first message shows", () -> bubble(scene, "primero de la prueba") != null);
        // The demo marks a sent message read (at once here), and that rightly draws it again: wait until it has.
        FxWait.until("the demo marks our messages read", () -> svc.messages(CHAT).stream().filter(Message::outgoing)
                .allMatch(m -> m.status() == app.unichat.model.MessageStatus.READ));
        FxWait.settled();
        Node before = fx(() -> bubble(scene, "primero de la prueba"));

        svc.sendText(CHAT, "segundo", null).get();
        FxWait.until("the new message shows", () -> bubble(scene, "segundo") != null);
        assertSame(before, fx(() -> bubble(scene, "primero de la prueba")), "still the same drawing");

        Message first = svc.messages(CHAT).stream().filter(m -> m.body().equals("primero de la prueba")).findFirst().orElseThrow();
        svc.editMessage(CHAT, first.id(), "primero cambiado").get();
        FxWait.until("the edit shows", () -> bubble(scene, "primero cambiado") != null);
        assertNotSame(before, fx(() -> bubble(scene, "primero cambiado")));
        fx(() -> {
            scene.getWindow().hide();
            return null;
        });
    }
}

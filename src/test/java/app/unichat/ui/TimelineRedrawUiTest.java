package app.unichat.ui;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A new message leaves the rows on screen alone, and what does change in one still shows. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class TimelineRedrawUiTest {
    private static final String CHAT = "!familia:demo";

    private static <T> T fx(Callable<T> c) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(20, TimeUnit.SECONDS);
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

    private static void until(Callable<Boolean> done) throws Exception {
        for (int i = 0; i < 300 && !done.call(); i++) Thread.sleep(50);
        assertTrue(done.call(), "in time");
    }

    @Test
    void onlyWhatChangedIsDrawnAgain() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        svc.sendText(CHAT, "primero de la prueba", null).get(5, TimeUnit.SECONDS);
        // The row just before a new one can change its corners when they join a group: this keeps "primero" away from that.
        svc.sendText(CHAT, "intermedio", null).get(5, TimeUnit.SECONDS);
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            MainView view = new MainView(svc, stage, new Notifier(() -> { }, id -> { }, () -> { }), () -> { }, u -> { });
            Scene sc = new Scene(view, 1000, 800);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.openChatId(CHAT);
            return sc;
        });
        until(() -> fx(() -> bubble(scene, "primero de la prueba") != null));
        Thread.sleep(500);
        Node before = fx(() -> bubble(scene, "primero de la prueba"));

        svc.sendText(CHAT, "segundo", null).get(5, TimeUnit.SECONDS);
        until(() -> fx(() -> bubble(scene, "segundo") != null));
        assertSame(before, fx(() -> bubble(scene, "primero de la prueba")), "still the same drawing");

        Message first = svc.messages(CHAT).stream().filter(m -> m.body().equals("primero de la prueba")).findFirst().orElseThrow();
        svc.editMessage(CHAT, first.id(), "primero cambiado").get(5, TimeUnit.SECONDS);
        until(() -> fx(() -> bubble(scene, "primero cambiado") != null));
        assertNotSame(before, fx(() -> bubble(scene, "primero cambiado")));
        fx(() -> {
            scene.getWindow().hide();
            return null;
        });
    }
}

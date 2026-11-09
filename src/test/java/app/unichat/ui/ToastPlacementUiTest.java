package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A notice ("Draft ready…") shows above the message box, never over it, however tall the box has grown. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ToastPlacementUiTest {
    private static Bounds on(Node n) {
        return n.localToScene(n.getLayoutBounds());
    }

    @Test
    void theNoticeLeavesTheBoxInSight() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        Stage[] stage = new Stage[1];
        Scene scene = FxWait.fx(() -> {
            stage[0] = new Stage();
            MainView view = new MainView(svc, stage[0], new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 760);
            Theme.register(sc);
            stage[0].setScene(sc);
            stage[0].show();
            view.openChatId("!familia:demo");
            return sc;
        });
        FxWait.until("the message box", () -> scene.getRoot().lookup(".composer-input") != null);
        for (String text : new String[]{"hola", "uno\ndos\ntres\ncuatro\ncinco\nseis"}) {
            FxWait.fx(() -> {
                ((TextArea) scene.getRoot().lookup(".composer-input")).setText(text);
                ((MainView) scene.getRoot()).toast("Borrador listo: revísalo antes de enviarlo");
                return null;
            });
            FxWait.settled();
            boolean clear = FxWait.fx(() -> {
                Bounds toast = on(scene.getRoot().lookup(".toast"));
                Bounds composer = on(((MainView) scene.getRoot()).thread().composer());
                return toast.getMaxY() <= composer.getMinY();
            });
            assertTrue(clear, "the notice sits above the box with " + text.lines().count() + " line(s)");
        }
        FxWait.fx(() -> { stage[0].close(); return null; });
    }
}

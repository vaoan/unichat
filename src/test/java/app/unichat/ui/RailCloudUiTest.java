package app.unichat.ui;

import app.unichat.ui.desktop.Notifier;
import app.unichat.ui.settings.SettingsWindow;
import app.unichat.cloud.Cloud;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Files going up show on the settings gear, a ring over it: the rail and the chat never move; a click goes to the detail. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class RailCloudUiTest {
    @Test
    void theRingIsDrawnOverTheGearAndItsClickGoesToTheDetail() throws Exception {
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
        MainView view = FxWait.fx(() -> {
            stage[0] = new Stage();
            stage[0].setOpacity(0);
            MainView v = new MainView(svc, stage[0], new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, u -> { });
            stage[0].setScene(new Scene(v, 1000, 700));
            stage[0].show();
            return v;
        });
        FxWait.settled();
        Button gear = FxWait.fx(() -> view.lookupAll(".rail-button").stream().map(n -> (Button) n)
                .filter(b -> "Ajustes".equals(b.getAccessibleText())).findFirst().orElseThrow());
        Node ring = FxWait.fx(() -> view.lookup(".rail-cloud-ring"));
        Node timeline = FxWait.fx(() -> view.lookup(".chat-list"));
        Bounds gearBefore = FxWait.fx(() -> gear.localToScene(gear.getBoundsInLocal()));
        Bounds listBefore = FxWait.fx(() -> timeline.localToScene(timeline.getBoundsInLocal()));
        assertFalse(FxWait.fx(ring::isVisible), "nothing going up: no ring");

        Cloud.Status up = new Cloud.Status(true, Instant.now(), null, 0, 9,
                new Cloud.Activity(Cloud.Step.UPLOADING, 2, 12, "foto.jpg", 450, 1000, 2_400_000), 0, null, 0, null);
        FxWait.fx(() -> { view.cloudChanged(up); return null; });
        FxWait.settled();
        assertTrue(FxWait.fx(ring::isVisible));
        assertEquals(gearBefore, FxWait.fx(() -> gear.localToScene(gear.getBoundsInLocal())), "the gear didn't move nor grow");
        assertEquals(listBefore, FxWait.fx(() -> timeline.localToScene(timeline.getBoundsInLocal())), "nor did anything beside it");
        assertTrue(FxWait.fx(() -> gear.getTooltip().getText()).startsWith("Nube: subiendo 3 de 12"), "hovering says what it's doing");

        FxWait.fx(() -> { gear.fire(); return null; });
        FxWait.until("Ajustes opens on Nube", () -> "cloud".equals(SettingsWindow.currentPage()));
        FxWait.fx(() -> {
            SettingsWindow.current().close();
            view.cloudChanged(new Cloud.Status(true, Instant.now(), null, 12, 0));
            return null;
        });
        assertFalse(FxWait.fx(ring::isVisible), "done: the ring goes");
        assertEquals("Ajustes", FxWait.fx(() -> gear.getTooltip().getText()));
        FxWait.fx(() -> { stage[0].close(); return null; });
        svc.stop();
    }
}

package app.unichat.ui.chats;

import app.unichat.ui.FxWait;
import app.unichat.autosave.AutoSave;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exploring a chat's past shows on top of the window, so its options can be closed, and can be stopped from there. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ExploreBarUiTest {
    @Test
    void itShowsWhereItIsHowFarAndStops() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        Map<String, AutoSave.Exploring> states = new ConcurrentHashMap<>();
        List<Runnable> listeners = new CopyOnWriteArrayList<>();
        List<String> stopped = new ArrayList<>();
        Runnable changed = () -> listeners.forEach(Runnable::run);
        Stage[] stage = new Stage[1];
        ExploreBar bar = FxWait.fx(() -> {
            ExploreBar b = new ExploreBar(() -> states.keySet().stream().filter(k -> states.get(k).running()).sorted().toList(),
                    states::get, stopped::add, listeners::add, listeners::remove, svc, id -> { });
            stage[0] = new Stage();
            stage[0].setOpacity(0);
            stage[0].setScene(new Scene(b, 800, 120));
            stage[0].show();
            return b;
        });
        assertFalse(FxWait.fx(bar::isVisible), "nothing explored: no bar");

        Instant nov3 = Instant.parse("2026-11-03T15:00:00Z");
        states.put("!familia:demo", new AutoSave.Exploring(true, nov3, false, 8, 2, 120,
                new AutoSave.Now(AutoSave.Doing.DOWNLOADING, nov3, "IMG_3944.MOV", 0.45)));
        changed.run();
        FxWait.until("the bar shows", bar::isVisible);
        String text = FxWait.fx(() -> texts(bar).toString());
        assertTrue(text.contains("IMG_3944.MOV") && text.contains("45 %") && text.contains("120 mensajes revisados"), text);
        assertTrue(text.contains("Familia"), "which chat: " + text);
        assertEquals(0.45, FxWait.fx(() -> ((ProgressBar) bar.lookup(".progress-bar")).getProgress()), 1e-9);

        Button stop = FxWait.fx(() -> bar.lookupAll(".button").stream().map(n -> (Button) n)
                .filter(b -> b.getText().equals("Detener")).findFirst().orElseThrow());
        FxWait.fx(() -> { stop.fire(); return null; });
        assertEquals(List.of("!familia:demo"), stopped);

        states.put("!familia:demo", new AutoSave.Exploring(false, nov3, true, 8, 2, 120, null));
        changed.run();
        FxWait.until("over: the bar goes", () -> !bar.isVisible());
        FxWait.fx(() -> { bar.dispose(); stage[0].close(); return null; });
        assertTrue(listeners.isEmpty(), "and stops listening");
        svc.stop();
    }

    /** Every text in {@code n}: labels, links, buttons. */
    private static List<String> texts(javafx.scene.Node n) {
        List<String> out = new ArrayList<>();
        if (n instanceof javafx.scene.control.Labeled l) out.add(l.getText());
        if (n instanceof javafx.scene.Parent p) for (javafx.scene.Node c : p.getChildrenUnmodifiable()) out.addAll(texts(c));
        return out;
    }
}

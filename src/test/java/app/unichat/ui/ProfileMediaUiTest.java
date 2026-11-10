package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The profile's media: photos keep their shape, kinds in tabs, and a chat can hide it. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ProfileMediaUiTest {
    @Test
    void photosKeepTheirShapeInTabsAndAChatCanHideThem() throws Exception {
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
        svc.setMediaHidden(id, false).get(); // the test data folder is the whole run's: start from a known state
        InfoPane pane = FxWait.fx(() -> {
            InfoPane p = new InfoPane(svc, () -> { }, t -> { });
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(p, 360, 900));
            stage.show();
            p.show(svc.chat(id));
            return p;
        });
        FxWait.until("a photo loaded", () -> pane.lookup(".media-thumb") instanceof ImageView v && v.getImage() != null);
        ImageView v = FxWait.fx(() -> (ImageView) pane.lookup(".media-thumb"));
        assertTrue(FxWait.fx(v::isPreserveRatio), "never stretched to the square");
        double shownRatio = FxWait.fx(() -> v.getLayoutBounds().getWidth() / v.getLayoutBounds().getHeight());
        double ratio = FxWait.fx(() -> v.getImage().getWidth() / v.getImage().getHeight());
        assertEquals(ratio, shownRatio, 0.05, "the picture's own shape");
        List<String> tabs = FxWait.fx(() -> pane.lookupAll(".media-tab").stream().map(n -> ((ToggleButton) n).getAccessibleText()).toList());
        assertTrue(tabs.stream().anyMatch(t -> t.startsWith("Fotos (")), tabs.toString());
        svc.setMediaHidden(id, true).get();
        FxWait.fx(() -> { pane.refresh(); return null; });
        assertTrue(FxWait.fx(() -> pane.lookup(".media-thumb") == null), "hidden for this chat");
        svc.setMediaHidden(id, false).get(); // and leave it as found
        FxWait.fx(() -> { pane.getScene().getWindow().hide(); return null; });
        svc.stop();
    }
}

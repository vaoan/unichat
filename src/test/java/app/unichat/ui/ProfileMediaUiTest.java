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
        // From the media to where it was shared: a file's button, a photo's menu.
        java.util.concurrent.atomic.AtomicReference<app.unichat.model.Message> jumped = new java.util.concurrent.atomic.AtomicReference<>();
        FxWait.fx(() -> { pane.setOnJump(jumped::set); return null; });
        javafx.scene.Node photoTile = FxWait.fx(() -> pane.lookup(".media-thumb-tile"));
        FxWait.fx(() -> {
            var p = photoTile.localToScreen(5, 5);
            javafx.event.Event.fireEvent(photoTile, new javafx.scene.input.ContextMenuEvent(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    5, 5, p.getX(), p.getY(), false, null));
            return null;
        });
        javafx.scene.control.ContextMenu menu = FxWait.fx(() -> javafx.stage.Window.getWindows().stream()
                .filter(w -> w instanceof javafx.scene.control.ContextMenu && w.isShowing()).map(w -> (javafx.scene.control.ContextMenu) w)
                .findFirst().orElseThrow());
        FxWait.fx(() -> { menu.getItems().get(0).fire(); menu.hide(); return null; });
        assertEquals(app.unichat.model.MessageType.IMAGE, jumped.get().type(), "the photo's message");
        ToggleButton files = FxWait.fx(() -> pane.lookupAll(".media-tab").stream().map(n -> (ToggleButton) n)
                .filter(t -> t.getAccessibleText().startsWith("Archivos")).findFirst().orElseThrow());
        FxWait.fx(() -> { files.fire(); return null; });
        javafx.scene.control.Button go = FxWait.fx(() -> pane.lookupAll(".button").stream().map(n -> (javafx.scene.control.Button) n)
                .filter(b -> "Ir al mensaje".equals(b.getAccessibleText())).findFirst().orElseThrow());
        FxWait.fx(() -> { go.fire(); return null; });
        assertTrue(jumped.get().type() == app.unichat.model.MessageType.FILE || jumped.get().type() == app.unichat.model.MessageType.AUDIO,
                "the file's message: " + jumped.get().type());

        svc.setMediaHidden(id, true).get();
        FxWait.fx(() -> { pane.refresh(); return null; });
        assertTrue(FxWait.fx(() -> pane.lookup(".media-thumb") == null), "hidden for this chat");
        svc.setMediaHidden(id, false).get(); // and leave it as found
        FxWait.fx(() -> { pane.getScene().getWindow().hide(); return null; });
        svc.stop();
    }
}

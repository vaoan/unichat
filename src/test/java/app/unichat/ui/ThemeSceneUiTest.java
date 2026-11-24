package app.unichat.ui;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A closed window's scene is let go: the theme kept every scene it styled, and a window closed keeps its scene, so each
 * photo viewer, video, toast and settings window ever opened stayed in memory (80 test windows ran the run out of heap).
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ThemeSceneUiTest {
    @Test
    @SuppressFBWarnings(value = "DM_GC", justification = "it measures what is kept after a collection (as its NOPMD says)")
    void aClosedWindowsSceneIsLetGoAndOneShownAgainIsStyledAgain() throws Exception {
        TimelineWindow.toolkit();
        int before = FxWait.fx(Theme::registered);
        Stage[] stage = new Stage[1];
        WeakReference<Scene> scene = FxWait.fx(() -> {
            stage[0] = new Stage();
            stage[0].setOpacity(0);
            Scene sc = new Scene(new StackPane(), 200, 100);
            Theme.register(sc); // before the window, as the app does
            stage[0].setScene(sc);
            stage[0].show();
            return new WeakReference<>(sc);
        });
        assertEquals(before + 1, FxWait.fx(Theme::registered), "followed while it shows");

        FxWait.onFx(stage[0]::hide);
        assertEquals(before, FxWait.fx(Theme::registered), "let go when hidden");
        FxWait.onFx(stage[0]::show);
        assertEquals(before + 1, FxWait.fx(Theme::registered), "shown again (from the tray): followed again");

        FxWait.onFx(() -> {
            stage[0].close();
            stage[0] = null;
        });
        FxWait.settled();
        FxWait.until("the closed window's scene is collected", () -> {
            System.gc(); // NOPMD: DoNotCallGarbageCollectionExplicitly, it measures what is kept after a collection
            return scene.get() == null;
        });
    }
}

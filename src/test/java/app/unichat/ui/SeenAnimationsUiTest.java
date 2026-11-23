package app.unichat.ui;

import app.unichat.support.Seen;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * While nobody can see Unichat (every window in the tray or minimised, the screen locked) what only moves for the eye
 * rests: the working line's seconds, and anything else through {@link Showing}. The animations of the timeline and
 * media packages are checked there ({@code timeline/SeenTimelineAnimationsUiTest}, {@code media/AnimationShowingUiTest}).
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SeenAnimationsUiTest {
    private final List<Stage> stages = new ArrayList<>();

    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get();
        Platform.setImplicitExit(false);
    }

    @AfterEach
    void close() throws Exception {
        Seen.reset(true);
        FxWait.fx(() -> { stages.forEach(Stage::close); return null; });
    }

    private Stage window(StackPane root) throws Exception {
        return FxWait.fx(() -> {
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(root, 200, 100));
            s.show();
            stages.add(s);
            return s;
        });
    }

    @Test
    void everyTimelineStopsWhenUnseenAndStartsWhenSeen() throws Exception {
        WorkingLine line = FxWait.fx(WorkingLine::new);
        Stage stage = window(FxWait.fx(() -> new StackPane(line.node())));
        FxWait.fx(() -> { line.start("Limpiando la nota"); return null; });
        assertTrue(FxWait.fx(() -> Showing.animating(line.node())), "counting while seen");

        Seen.reset(false); // from another thread, as the screen's source does
        FxWait.until("resting while nobody can see it", () -> !Showing.animating(line.node()));
        Seen.reset(true);
        FxWait.until("counting again once seen", () -> Showing.animating(line.node()));

        FxWait.fx(() -> { stage.setIconified(true); return null; });
        FxWait.until("resting while its window is minimised", () -> !Showing.animating(line.node()));
        FxWait.fx(() -> { stage.setIconified(false); return null; });
        FxWait.until("counting again once restored", () -> Showing.animating(line.node()));

        FxWait.fx(() -> { line.stop(); return null; });
        Seen.reset(false);
        Seen.reset(true);
        FxWait.fx(() -> null); // the change reached the FX thread
        assertFalse(FxWait.fx(() -> Showing.animating(line.node())), "done: seen again doesn't start it");
    }

    @Test
    void onlyWatchesWhoseWindowShowsFollowSeenAndNoneStaysOnceCancelled() throws Exception {
        int before = FxWait.fx(Showing::followingSeen);
        StackPane node = FxWait.fx(StackPane::new);
        List<String> heard = new ArrayList<>();
        Stage stage = window(FxWait.fx(() -> new StackPane(node)));
        Showing.Watch watch = FxWait.fx(() -> Showing.whileShowing(node, () -> heard.add("start"), () -> heard.add("stop")));
        assertEquals(before + 1, FxWait.fx(Showing::followingSeen), "its window shows");

        FxWait.fx(() -> { stage.close(); return null; });
        assertEquals(before, FxWait.fx(Showing::followingSeen), "a closed window's node isn't held by Seen");
        Seen.reset(false);
        Seen.reset(true);
        FxWait.fx(() -> null);
        assertEquals(List.of("start", "stop"), FxWait.fx(() -> List.copyOf(heard)), "seen again doesn't start a closed window's work");

        FxWait.fx(() -> { stage.show(); return null; });
        assertEquals(before + 1, FxWait.fx(Showing::followingSeen), "shown again");
        FxWait.fx(() -> { watch.cancel(); return null; });
        assertEquals(before, FxWait.fx(Showing::followingSeen), "cancelled: Seen no longer reaches it");
    }
}

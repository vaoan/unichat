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
        FxWait.onFx(() -> stages.forEach(Stage::close));
    }

    private Stage window(StackPane root) throws InterruptedException {
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
        FxWait.onFx(() -> line.start("Limpiando la nota"));
        java.util.concurrent.Callable<Boolean> spinning = () -> line.node().lookupAll(".progress-indicator").stream()
                .anyMatch(n -> ((javafx.scene.control.ProgressIndicator) n).isIndeterminate());
        assertTrue(FxWait.fx(() -> Showing.animating(line.node())), "counting while seen");
        assertTrue(FxWait.fx(spinning::call), "its wheel turns while seen");

        Seen.reset(false); // from another thread, as the screen's source does
        FxWait.until("resting while nobody can see it", () -> !Showing.animating(line.node()) && !spinning.call());
        Seen.reset(true);
        FxWait.until("counting again once seen", () -> Showing.animating(line.node()) && spinning.call());

        FxWait.onFx(() -> stage.setIconified(true));
        FxWait.until("resting while its window is minimised", () -> !Showing.animating(line.node()) && !spinning.call());
        FxWait.onFx(() -> stage.setIconified(false));
        FxWait.until("counting again once restored", () -> Showing.animating(line.node()) && spinning.call());

        FxWait.onFx(line::stop);
        Seen.reset(false);
        Seen.reset(true);
        FxWait.fx(() -> null); // the change reached the FX thread
        assertFalse(FxWait.fx(() -> Showing.animating(line.node())), "done: seen again doesn't start it");
    }

    @Test
    void aTaskBarsBarRestsWhileUnseenAndKeepsWhatItSays() throws Exception {
        TaskBar task = FxWait.fx(() -> new TaskBar("test-bar") { });
        Stage stage = window(FxWait.fx(() -> new StackPane(task)));
        java.util.concurrent.Callable<Boolean> spinning = task.bar::isIndeterminate;
        FxWait.onFx(() -> task.bar.setProgress(javafx.scene.control.ProgressBar.INDETERMINATE_PROGRESS));
        assertTrue(FxWait.fx(spinning::call), "it moves while seen");

        Seen.reset(false);
        FxWait.until("resting while nobody can see it", () -> !spinning.call());
        Seen.reset(true);
        FxWait.until("moving again once seen", spinning);

        FxWait.onFx(() -> stage.setIconified(true));
        FxWait.until("resting while its window is minimised", () -> !spinning.call());
        FxWait.fx(() -> {
            task.bar.setProgress(javafx.scene.control.ProgressBar.INDETERMINATE_PROGRESS); // told again meanwhile
            return null;
        });
        assertFalse(FxWait.fx(spinning::call), "told to move while minimised, it still rests");
        FxWait.onFx(() -> stage.setIconified(false));
        FxWait.until("moving again once restored", spinning);

        FxWait.onFx(() -> task.bar.setProgress(0.4));
        Seen.reset(false);
        Seen.reset(true);
        FxWait.fx(() -> null); // both changes reached the FX thread
        assertEquals(0.4, FxWait.fx(task.bar::getProgress), "a count it showed stays as it was");
    }

    /** Seen's listeners run under its lock: Showing's work is handed to the FX thread, even when set from there. */
    @Test
    void workNeverRunsInsideSeensOwnCall() throws Exception {
        StackPane node = FxWait.fx(StackPane::new);
        List<Boolean> underSeensLock = new ArrayList<>();
        window(FxWait.fx(() -> new StackPane(node)));
        Runnable look = () -> underSeensLock.add(Thread.holdsLock(Seen.get()));
        FxWait.fx(() -> {
            Showing.whileShowing(node, look, look);
            underSeensLock.clear(); // the first start, from whileShowing itself
            Seen.reset(false);
            return null;
        });
        FxWait.onFx(() -> Seen.reset(true));  // after the stop handed on by the first has run
        FxWait.fx(() -> null); // and the start
        assertEquals(List.of(false, false), FxWait.fx(() -> List.copyOf(underSeensLock)), "stop and start, outside Seen's call");
    }

    @Test
    void onlyWatchesWhoseWindowShowsFollowSeenAndNoneStaysOnceCancelled() throws Exception {
        int before = FxWait.fx(Showing::followingSeen);
        StackPane node = FxWait.fx(StackPane::new);
        List<String> heard = new ArrayList<>();
        Stage stage = window(FxWait.fx(() -> new StackPane(node)));
        Showing.Watch watch = FxWait.fx(() -> Showing.whileShowing(node, () -> heard.add("start"), () -> heard.add("stop")));
        assertEquals(before + 1, FxWait.fx(Showing::followingSeen), "its window shows");

        FxWait.onFx(stage::close);
        assertEquals(before, FxWait.fx(Showing::followingSeen), "a closed window's node isn't held by Seen");
        Seen.reset(false);
        Seen.reset(true);
        FxWait.fx(() -> null);
        assertEquals(List.of("start", "stop"), FxWait.fx(() -> List.copyOf(heard)), "seen again doesn't start a closed window's work");

        FxWait.onFx(stage::show);
        assertEquals(before + 1, FxWait.fx(Showing::followingSeen), "shown again");
        FxWait.onFx(watch::cancel);
        assertEquals(before, FxWait.fx(Showing::followingSeen), "cancelled: Seen no longer reaches it");
    }
}

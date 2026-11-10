package app.unichat.ui.emoji;

import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

/**
 * How fast the Emojis tab builds and scrolls from top to bottom (real display, {@code -Dunichat.perfTests=true}); prints
 * the numbers to compare a change against. Each step waits for the window to have laid out and drawn it.
 */
@EnabledIfSystemProperty(named = "unichat.perfTests", matches = "true")
class EmojiScrollPerfUiTest {
    private static final int STEPS = 120;

    @Test
    void scrollingTheWholeList() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("emoji-perf");
        app.unichat.model.MediaLibrary library = new app.unichat.model.MediaLibrary(dir.resolve("media-library.json"));
        long t0 = System.nanoTime();
        Parent panel = FxWait.fx(() -> {
            Parent p = EmojiGrid.panel(library, e -> { }, t -> { }).node();
            Stage stage = new Stage();
            Scene scene = new Scene(new StackPane(p), 330, 700);
            scene.getStylesheets().add(EmojiScrollPerfUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            return p;
        });
        FxWait.settled();
        long built = System.nanoTime() - t0;
        ScrollBar bar = FxWait.fx(() -> panel.lookupAll(".scroll-bar").stream().map(n -> (ScrollBar) n)
                .filter(b -> b.getOrientation() == javafx.geometry.Orientation.VERTICAL).findFirst().orElseThrow());
        long t1 = System.nanoTime();
        for (int i = 1; i <= STEPS; i++) {
            double to = (double) i / STEPS;
            FxWait.fx(() -> {
                bar.setValue(bar.getMin() + (bar.getMax() - bar.getMin()) * to);
                return null;
            });
            FxWait.settled();
        }
        long scrolled = System.nanoTime() - t1;
        long nodes = FxWait.fx(() -> count(panel));
        System.out.printf("EMOJI PERF: built and shown in %d ms, %d nodes, %.1f ms per scroll step (%d steps)%n",
                built / 1_000_000, nodes, scrolled / 1e6 / STEPS, STEPS);
    }

    private static long count(Node n) {
        long c = 1;
        if (n instanceof Parent p) for (Node k : p.getChildrenUnmodifiable()) c += count(k);
        return c;
    }
}

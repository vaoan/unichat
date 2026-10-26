package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for "an image only opened on the second click": one press + release opens the viewer.
 * Needs a display: {@code -Dunichat.uiTests=true}; windows are invisible.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class MediaClickUiTest {
    @Test
    void onePressAndReleaseOpensTheViewer() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setX(-10000);
            MainView view = new MainView(new MockChatService(), stage, new Notifier(() -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 800);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.openChatId("!familia:demo");
            return sc;
        });
        // Wait for the photo to render (a fixed sleep was flaky under load).
        for (int i = 0; i < 100 && !fx(() -> scene.getRoot().lookup(".media-box") != null); i++) Thread.sleep(100);
        // Count only viewer windows: the demo's simulated incoming messages open notification cards too.
        int before = fx(MediaClickUiTest::viewers);
        fx(() -> {
            Node media = scene.getRoot().lookup(".media-box");
            assertTrue(media != null, "the demo chat has a photo");
            Event.fireEvent(media, mouse(MouseEvent.MOUSE_PRESSED, media, true));
            Event.fireEvent(media, mouse(MouseEvent.MOUSE_RELEASED, media, true));
            return null;
        });
        Thread.sleep(300);
        assertEquals(before + 1, (int) fx(MediaClickUiTest::viewers), "viewer opened on the first click");
        boolean viewer = fx(() -> Window.getWindows().stream().anyMatch(w -> w.getScene() != null
                && w.getScene().getRoot().getStyleClass().contains("media-viewer")));
        assertTrue(viewer);
        fx(() -> {
            Window.getWindows().stream().filter(w -> w.getScene().getRoot().getStyleClass().contains("media-viewer"))
                    .toList().forEach(Window::hide);
            return null;
        });
    }

    private static int viewers() {
        return (int) Window.getWindows().stream()
                .filter(w -> w.getScene() != null && w.getScene().getRoot().getStyleClass().contains("media-viewer")).count();
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, Node n, boolean still) {
        return new MouseEvent(type, 10, 10, 10, 10, MouseButton.PRIMARY, 1, false, false, false, false,
                true, false, false, true, false, still, null);
    }

    private static <T> T fx(Supplier<T> s) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(s.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }
}

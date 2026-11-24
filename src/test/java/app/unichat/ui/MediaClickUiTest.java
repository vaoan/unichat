package app.unichat.ui;

import app.unichat.ui.desktop.Notifier;
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
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            MainView view = new MainView(new MockChatService(), stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 800);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.openChatId("!family:demo");
            return sc;
        });
        FxWait.until("the photo renders", () -> scene.getRoot().lookup(".media-box") != null);
        // Count only viewer windows: the demo's simulated incoming messages open notification cards too.
        int before = fx(MediaClickUiTest::viewers);
        FxWait.onFx(() -> {
            Node media = scene.getRoot().lookup(".media-box");
            assertNotNull(media, "the demo chat has a photo");
            Event.fireEvent(media, mouse(MouseEvent.MOUSE_PRESSED, true));
            Event.fireEvent(media, mouse(MouseEvent.MOUSE_RELEASED, true));
        });
        FxWait.until("a viewer opens", () -> viewers() > before);
        FxWait.settled(); // and only one
        assertEquals(before + 1, (int) fx(MediaClickUiTest::viewers), "viewer opened on the first click");
        boolean viewer = fx(() -> Window.getWindows().stream().anyMatch(w -> w.getScene() != null
                && w.getScene().getRoot().getStyleClass().contains("media-viewer")));
        assertTrue(viewer);
        FxWait.onFx(() -> {
            Window.getWindows().stream().filter(w -> w.getScene() != null && w.getScene().getRoot().getStyleClass().contains("media-viewer"))
                    .toList().forEach(Window::hide);
        });
    }

    private static int viewers() {
        return (int) Window.getWindows().stream()
                .filter(w -> w.getScene() != null && w.getScene().getRoot().getStyleClass().contains("media-viewer")).count();
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, boolean still) {
        return new MouseEvent(type, 10, 10, 10, 10, MouseButton.PRIMARY, 1, false, false, false, false,
                true, false, false, true, false, still, null);
    }

    private static <T extends @Nullable Object, E extends Exception> T fx(FxWait.FxCall<T, E> c) throws E, InterruptedException {
        return FxWait.fx(c);
    }
}

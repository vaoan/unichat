package app.unichat.ui.media;

import app.unichat.ui.chats.InfoPane;
import app.unichat.ui.FxWait;
import app.unichat.ui.Theme;
import app.unichat.model.Chat;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The info panel's photo opens large in the chat's photo viewer; initials alone open nothing. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class InfoAvatarUiTest {
    private Stage stage;

    @BeforeAll
    static void toolkit() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
    }

    @AfterEach
    void close() throws Exception {
        FxWait.fx(() -> {
            Window.getWindows().stream().filter(w -> w.getScene() != null
                    && w.getScene().getRoot().getStyleClass().contains("media-viewer")).toList().forEach(Window::hide);
            if (stage != null) stage.close();
            return null;
        });
    }

    /**
     * The photo viewer this test opened: the one titled with the chat's name. "The first media viewer" was, on Linux, a
     * video viewer another test opened as it ended, and this test waited three minutes for a photo in it.
     */
    private static Optional<Window> viewer(String title) {
        return Window.getWindows().stream().filter(w -> w instanceof Stage s && title.equals(s.getTitle()) && w.getScene() != null
                && w.getScene().getRoot().getStyleClass().contains("media-viewer")).findFirst();
    }

    private InfoPane panel(MockChatService svc, Chat c) throws Exception {
        return FxWait.fx(() -> {
            InfoPane info = new InfoPane(svc, () -> { }, t -> { });
            stage = new Stage();
            stage.setScene(new Scene(info, 340, 700));
            Theme.register(stage.getScene());
            stage.show();
            info.show(c);
            return info;
        });
    }

    @Test
    void thePhotoOpensLargeInThePhotoViewer() throws Exception {
        MockChatService svc = new MockChatService();
        Chat mama = svc.chat("!mama:demo");
        mama.setAvatarUrl("mxc://demo/mama.png");
        InfoPane info = panel(svc, mama);
        Node avatar = FxWait.fx(() -> info.lookup(".avatar-enlargeable"));
        FxWait.fx(() -> {
            Event.fireEvent(avatar, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        try {
            FxWait.until("the photo viewer shows the photo", () -> viewer(mama.title()).map(w -> w.getScene().getRoot().lookupAll("*").stream()
                    .anyMatch(n -> n instanceof PhotoPane p && p.view().getImage() != null)).orElse(false));
        } catch (AssertionError | RuntimeException e) {
            // What it saw instead, for a machine where it doesn't (Linux CI): no viewer, or one with no picture, and why.
            throw new AssertionError("viewer: " + FxWait.fx(() -> viewer(mama.title()).map(w -> w.getScene().getRoot().lookupAll("*").stream()
                    .filter(n -> n instanceof PhotoPane).map(n -> {
                        var img = ((PhotoPane) n).view().getImage();
                        return img == null ? "no image" : img.isError() ? "error " + img.getException() : "image";
                    }).toList().toString()).orElse("none")), e);
        }
        assertEquals(mama.title(), FxWait.fx(() -> ((Stage) viewer(mama.title()).orElseThrow()).getTitle()));
    }

    @Test
    void initialsAloneOpenNothing() throws Exception {
        MockChatService svc = new MockChatService();
        Chat mama = svc.chat("!mama:demo");
        mama.setAvatarUrl(null);
        InfoPane info = panel(svc, mama);
        assertNull(FxWait.fx(() -> info.lookup(".avatar-enlargeable")), "no photo: nothing to enlarge");
    }
}

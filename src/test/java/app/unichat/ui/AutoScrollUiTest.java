package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The open chat follows the newest message: on open, when content grows late, and when you send. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AutoScrollUiTest {
    private static final String CHAT = "!andres:demo";
    private MockChatService svc;
    private MainView view;

    @BeforeAll
    static void toolkit() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
    }

    /** A chat taller than the window but with fewer than 20 messages (so opening it also loads older ones). */
    private Scene start() throws Exception {
        svc = new MockChatService();
        for (int i = 0; i < 12; i++) {
            svc.sendText(CHAT, "Mensaje largo " + i + "\nsegunda línea\ntercera línea\ncuarta línea", null).get(5, TimeUnit.SECONDS);
        }
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1000, 520);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        Thread.sleep(300);
        fx(() -> { view.openChatId("!familia:demo"); return null; });
        Thread.sleep(300);
        fx(() -> { view.openChatId(CHAT); return null; });
        Thread.sleep(900);
        return scene;
    }

    @SuppressWarnings("unchecked")
    private static ListView<Object> timeline(Scene s) {
        return (ListView<Object>) s.getRoot().lookup(".timeline");
    }

    /** The last row is the last visible cell and its bottom edge is inside the viewport. */
    private static String bottomState(Scene s) throws Exception {
        return fx(() -> {
            ListView<Object> list = timeline(s);
            VirtualFlow<?> flow = (VirtualFlow<?>) list.lookup(".virtual-flow");
            IndexedCell<?> last = flow.getLastVisibleCell();
            int lastIndex = list.getItems().size() - 1;
            if (last == null) return "no cells";
            double bottom = last.localToScene(last.getBoundsInLocal()).getMaxY();
            double viewport = flow.localToScene(flow.getBoundsInLocal()).getMaxY();
            boolean ok = last.getIndex() == lastIndex && bottom <= viewport + 2;
            return ok ? "bottom" : "last visible " + last.getIndex() + "/" + lastIndex + ", bottom " + bottom + " vs " + viewport;
        });
    }

    @Test
    void openingAShortChatEndsAtTheNewestMessage() throws Exception {
        Scene scene = start();
        assertTrue(fx(() -> timeline(scene).lookupAll(".scroll-bar").stream()
                .anyMatch(n -> n instanceof ScrollBar sb && sb.isVisible() && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL)),
                "precondition: the chat is taller than the window");
        assertTrue("bottom".equals(bottomState(scene)), bottomState(scene));
        svc.stop();
    }

    @Test
    void contentThatGrowsAfterwardsKeepsTheBottomInView() throws Exception {
        Scene scene = start();
        // Like a photo or sticker finishing its download: the newest bubble gets taller after it was shown.
        fx(() -> {
            VirtualFlow<?> flow = (VirtualFlow<?>) timeline(scene).lookup(".virtual-flow");
            IndexedCell<?> last = flow.getLastVisibleCell();
            if (last.getGraphic() instanceof Region r) r.setMinHeight(r.getHeight() + 220);
            return null;
        });
        Thread.sleep(600);
        assertTrue("bottom".equals(bottomState(scene)), bottomState(scene));
        svc.sendText(CHAT, "nuevo", null);
        Thread.sleep(600);
        assertTrue("bottom".equals(bottomState(scene)), "still following new messages: " + bottomState(scene));
        svc.stop();
    }

    @Test
    void sendingWhileScrolledUpJumpsToYourMessage() throws Exception {
        Scene scene = start();
        // Scroll up with the wheel, as a person would (a programmatic scroll doesn't unpin the bottom).
        for (int i = 0; i < 8; i++) {
            fx(() -> {
                javafx.scene.Node flow = timeline(scene).lookup(".virtual-flow");
                javafx.event.Event.fireEvent(flow, new javafx.scene.input.ScrollEvent(javafx.scene.input.ScrollEvent.SCROLL,
                        200, 200, 200, 200, false, false, false, false, false, false, 0, 120, 0, 120,
                        javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                        javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
                return null;
            });
            Thread.sleep(60);
        }
        Thread.sleep(400);
        assertTrue(!"bottom".equals(bottomState(scene)), "precondition: scrolled up");
        svc.sendText(CHAT, "lo que acabo de escribir", null);
        Thread.sleep(700);
        assertTrue("bottom".equals(bottomState(scene)), bottomState(scene));
        svc.stop();
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }
}

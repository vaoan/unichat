package app.unichat.ui.timeline;

import app.unichat.model.Message;
import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.desktop.Notifier;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reacting is meant, never a side effect: a double click on a message (opening its photo, selecting its words) reacted
 * with ❤️ to it. Nothing but a reaction's own button, menu item or key reacts.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class NoAccidentalReactionUiTest {
    private static final String CHAT = "!familia:demo";
    private MockChatService svc;
    private Stage stage;
    private MainView view;
    private ThreadPane thread;

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

    @BeforeEach
    void open() throws Exception {
        svc = new MockChatService();
        svc.noAutoReplies();
        thread = FxWait.fx(() -> {
            stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            stage.setScene(new Scene(view, 900, 700));
            stage.show();
            view.openChatId(CHAT);
            return find(view);
        });
        FxWait.settled();
    }

    @AfterEach
    void close() throws Exception {
        svc.stop();
        FxWait.fx(() -> {
            view.dispose();
            stage.close();
            stage.getScene().setRoot(new javafx.scene.layout.Pane());
            return null;
        });
    }

    private static ThreadPane find(Node n) {
        if (n instanceof ThreadPane t) return t;
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) {
            ThreadPane t = find(c);
            if (t != null) return t;
        }
        return null;
    }

    private Map<String, Set<String>> reactions() {
        return Map.copyOf(svc.messages(CHAT).stream().collect(java.util.stream.Collectors.toMap(Message::id, m -> m.reactions().keySet())));
    }

    @Test
    void aDoubleClickOnAMessageNeverReacts() throws Exception {
        Map<String, Set<String>> before = reactions();
        List<Node> bubbles = FxWait.fx(() -> List.copyOf(thread.lookupAll(".bubble")));
        FxWait.fx(() -> {
            for (Node b : bubbles) {
                javafx.geometry.Bounds at = b.localToScene(b.getBoundsInLocal());
                for (int clicks = 1; clicks <= 2; clicks++) {
                    Event.fireEvent(b, new MouseEvent(MouseEvent.MOUSE_CLICKED, 2, 2, at.getMinX() + 2, at.getMinY() + 2,
                            MouseButton.PRIMARY, clicks, false, false, false, false, true, false, false, true, false, false, null));
                }
            }
            return null;
        });
        FxWait.settled();
        assertEquals(before, reactions(), "a double click on every message, photos too, reacted to none");
    }

    @Test
    void aDigitTypedOnTheMessagesWritesItNeverReacts() throws Exception {
        Map<String, Set<String>> before = reactions();
        FxWait.fx(() -> { thread.focusTimeline(); return null; });
        for (var type : List.of(javafx.scene.input.KeyEvent.KEY_PRESSED, javafx.scene.input.KeyEvent.KEY_TYPED)) {
            FxWait.fx(() -> {
                Node target = stage.getScene().getFocusOwner() != null ? stage.getScene().getFocusOwner() : stage.getScene().getRoot();
                boolean typed = type == javafx.scene.input.KeyEvent.KEY_TYPED;
                Event.fireEvent(target, new javafx.scene.input.KeyEvent(type, typed ? "1" : "", typed ? "" : "1",
                        typed ? javafx.scene.input.KeyCode.UNDEFINED : javafx.scene.input.KeyCode.DIGIT1, false, false, false, false));
                return null;
            });
        }
        FxWait.settled();
        assertEquals(before, reactions(), "no reaction");
        assertEquals("1", FxWait.fx(() -> ((javafx.scene.control.TextArea) thread.lookup(".composer-input")).getText()),
                "it went to the message box, as any other letter");
    }

    @Test
    void theMenuStillReacts() throws Exception {
        Message last = svc.messages(CHAT).getLast();
        String first = FxWait.fx(() -> thread.actions().quick().getFirst());
        javafx.scene.control.MenuItem react = FxWait.fx(() -> thread.actions().messageMenu(last).getItems().stream()
                .filter(i -> ("Reaccionar " + first).equals(i.getText())).findFirst().orElseThrow());
        FxWait.fx(() -> { react.fire(); return null; });
        FxWait.until("reacted, as meant", () -> last.reactions().containsKey(first));
    }
}

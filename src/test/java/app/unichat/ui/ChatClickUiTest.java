package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clicking a chat opens it without JavaFX failing underneath: opening changed the list's selection while the list was
 * still telling its own listeners about the click (IndexOutOfBoundsException in ListViewBehavior).
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ChatClickUiTest {
    private MockChatService svc;
    private Stage stage;
    private MainView view;
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

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
        Platform.setImplicitExit(false);
    }

    @AfterEach
    void close() throws Exception {
        svc.stop();
        fx(() -> {
            stage.close();
            return null;
        });
    }

    @Test
    void clickingChatsOneAfterAnotherOpensEachWithoutErrors() throws Exception {
        clickChats(false);
    }

    /** Opening an unread chat reads it, and "No leídos" drops it from the list while the click is still selecting it. */
    @Test
    void clickingAnUnreadChatUnderUnreadOnlyOpensItWithoutErrors() throws Exception {
        clickChats(true);
    }

    private void clickChats(boolean unreadOnly) throws Exception {
        svc = new MockChatService();
        Scene scene = fx(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((t, e) -> failures.add(e));
            stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 760);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        Thread.sleep(500);
        // A real click focuses the list first: that's when selecting a row opens its chat.
        boolean focused = fx(() -> {
            javafx.scene.Node list = scene.getRoot().lookup(".chat-list");
            list.requestFocus();
            return list.isFocused();
        });
        assertTrue(focused, "the list has the focus");
        if (unreadOnly) {
            fx(() -> {
                ChatListPane pane = (ChatListPane) scene.getRoot().lookup(".chat-list-pane");
                pane.filterProperty().set(pane.filterProperty().get().withUnreadOnly(true));
                return null;
            });
            Thread.sleep(300);
        }
        for (int i = 0; i < (unreadOnly ? 2 : 4); i++) {
            int row = i;
            String wanted = unreadOnly ? List.of("!mama:demo", "!familia:demo").get(i) : null;
            String clicked = fx(() -> {
                List<ListCell<?>> cells = scene.getRoot().lookupAll(".chat-list .list-cell").stream()
                        .<ListCell<?>>map(n -> (ListCell<?>) n).filter(c -> c.getItem() instanceof Chat ch && !ch.space() && plainChat(ch)).toList();
                ListCell<?> cell = wanted == null ? cells.get(row)
                        : cells.stream().filter(c -> wanted.equals(((Chat) c.getItem()).id())).findFirst().orElseThrow();
                String id = ((Chat) cell.getItem()).id(); // the cell may show another chat once this one is read
                Event.fireEvent(cell, mouse(MouseEvent.MOUSE_PRESSED));
                Event.fireEvent(cell, mouse(MouseEvent.MOUSE_RELEASED));
                Event.fireEvent(cell, mouse(MouseEvent.MOUSE_CLICKED));
                return id;
            });
            for (int w = 0; w < 40 && !clicked.equals(fx(() -> view.currentChatId())); w++) Thread.sleep(50);
            assertEquals(clicked, fx(() -> view.currentChatId()), "the clicked chat opens");
        }
        Thread.sleep(300);
        assertTrue(failures.isEmpty(), "no errors on the UI thread: " + failures);
    }

    /** A chat that opens on a click (not a server row, whose click shows its channels). */
    private boolean plainChat(Chat ch) {
        Chat real = svc.chat(ch.id());
        return real != null && !real.space() && svc.chats().stream().noneMatch(o -> o.spaceParents().contains(ch.id()));
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type) {
        return new MouseEvent(type, 10, 10, 10, 10, MouseButton.PRIMARY, 1, false, false, false, false,
                true, false, false, true, false, true, null);
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

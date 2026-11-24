package app.unichat.ui;

import app.unichat.Present;
import app.unichat.ui.chats.InfoPane;
import app.unichat.ui.desktop.Notifier;
import app.unichat.service.MockChatService;
import java.util.concurrent.ExecutionException;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A search box's ✕ and Esc empty it; ⌘L (Ctrl+L) goes from anywhere to the chat's message box; the info panel isn't
 * built again (a flash) when nothing on it changed.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SearchKeysUiTest {
    private static <T> T fx(Callable<T> c) throws InterruptedException, ExecutionException {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get();
    }

    private static void press(Node target, KeyCode code, boolean shortcut) throws InterruptedException, ExecutionException {
        FxWait.onFx(() -> {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut, false, shortcut));
        });
    }

    @Test
    void theInfoPanelIsBuiltAgainOnlyWhenWhatItShowsChanges() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        MockChatService svc = new MockChatService();
        InfoPane info = fx(() -> new InfoPane(svc, () -> { }, t -> { }));
        javafx.scene.layout.VBox box = fx(() -> (javafx.scene.layout.VBox) info.getContent());
        FxWait.onFx(() -> {
            info.show(Present.of(svc.chat("!family:demo")));
        });
        Node first = fx(() -> box.getChildren().get(0));
        FxWait.onFx(() -> {
            info.refresh();
        });
        assertTrue(fx(() -> box.getChildren().get(0) == first), "nothing changed: the same nodes");
        svc.setFavorite("!family:demo", !Present.of(svc.chat("!family:demo")).favorite()).get();
        FxWait.onFx(() -> {
            info.refresh();
        });
        assertFalse(fx(() -> box.getChildren().get(0) == first), "a change: built again");
    }

    @Test
    void theCrossAndEscClearAndShortcutLGoesToTheMessageBox() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            MainView view = new MainView(new MockChatService(), stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 700);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.openChatId("!family:demo");
            return sc;
        });
        TextField search = fx(() -> (TextField) scene.getRoot().lookup(".search-box .search-field"));
        Button clear = fx(() -> (Button) scene.getRoot().lookup(".search-box .search-clear"));
        assertFalse(fx(clear::isVisible), "no ✕ while it's empty");

        FxWait.onFx(() -> {
            search.requestFocus();
            search.setText("fam");
        });
        assertTrue(fx(clear::isVisible));
        FxWait.onFx(() -> {
            clear.fire();
        });
        assertEquals("", fx(search::getText));

        FxWait.onFx(() -> {
            search.setText("fam");
        });
        press(search, KeyCode.ESCAPE, false);
        assertEquals("", fx(search::getText), "Esc empties it");

        press(search, KeyCode.L, true);
        TextArea box = fx(() -> (TextArea) scene.getRoot().lookup(".composer-row .text-area"));
        assertTrue(fx(() -> scene.getFocusOwner() == box), "⌘L: the message box has the focus");
    }
}

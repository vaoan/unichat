package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Changing the language (or logging out) builds a new window's contents on the same stage. The old one listened to
 * the stage and was never told to stop: each time the window got the focus it marked its chat read, unseen, and it
 * stayed in memory with everything it showed.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class RelocalizeUiTest {
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

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }

    private static MainView view(MockChatService svc, Stage stage) {
        return new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
    }

    @Test
    void theWindowBuiltBeforeIsLetGo() throws Exception {
        MockChatService svc = new MockChatService();
        Stage[] stage = new Stage[1];
        WeakReference<MainView> old = fx(() -> {
            stage[0] = new Stage();
            stage[0].setOpacity(0);
            MainView before = view(svc, stage[0]);
            stage[0].setScene(new Scene(before, 1000, 700));
            stage[0].show();
            before.openChatId("!familia:demo");
            before.dispose();
            MainView fresh = view(svc, stage[0]);
            stage[0].getScene().setRoot(fresh);
            fresh.openChatId("!familia:demo");
            return new WeakReference<>(before);
        });
        // Held by the stage's listeners, it marked its chat read each time the window got the focus.
        FxWait.until("nothing holds the old window's contents", () -> {
            System.gc();
            return old.get() == null;
        });
        fx(() -> { stage[0].close(); svc.stop(); return null; });
    }
}

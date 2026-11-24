package app.unichat.ui;

import app.unichat.ui.desktop.Notifier;
import app.unichat.service.MockChatService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
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

    private static <T extends @Nullable Object, E extends Exception> T fx(FxWait.FxCall<T, E> c) throws E, InterruptedException {
        return FxWait.fx(c);
    }

    private static MainView view(MockChatService svc, Stage stage) {
        return new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
    }

    @Test
    @SuppressFBWarnings(value = "DM_GC", justification = "it measures what is kept after a collection (as its NOPMD says)")
    void theWindowBuiltBeforeIsLetGo() throws Exception {
        MockChatService svc = new MockChatService();
        Stage[] stage = new Stage[1];
        WeakReference<MainView> old = fx(() -> {
            stage[0] = new Stage();
            stage[0].setOpacity(0);
            MainView before = view(svc, stage[0]);
            stage[0].setScene(new Scene(before, 1000, 700));
            stage[0].show();
            before.openChatId("!family:demo");
            before.dispose();
            MainView fresh = view(svc, stage[0]);
            stage[0].getScene().setRoot(fresh);
            fresh.openChatId("!family:demo");
            return new WeakReference<>(before);
        });
        // Held by the stage's listeners, it marked its chat read each time the window got the focus.
        FxWait.until("nothing holds the old window's contents", () -> {
            System.gc(); // NOPMD: DoNotCallGarbageCollectionExplicitly, it measures what is kept after a collection
            return old.get() == null;
        });
        FxWait.onFx(() -> {
            stage[0].close();
            svc.stop();
        });
    }
}

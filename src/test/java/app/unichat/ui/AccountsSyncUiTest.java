package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ajustes → Cuentas: Telegram has "Sincronizar chats" to bring chats beyond the login-time limit. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AccountsSyncUiTest {
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

    @Test
    void telegramCanBeAskedToSyncItsChats() throws Exception {
        MockChatService svc = new MockChatService();
        List<String> toasts = new CopyOnWriteArrayList<>();
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            AccountsPane pane = new AccountsPane(svc, () -> { }, toasts::add, id -> { });
            Scene sc = new Scene(pane, 700, 600);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        Thread.sleep(300);
        List<Button> sync = fx(() -> scene.getRoot().lookupAll(".button").stream().map(n -> (Button) n)
                .filter(b -> "Sincronizar chats".equals(b.getText())).toList());
        assertEquals(1, sync.size(), "only Telegram has it");
        assertTrue(fx(() -> sync.get(0).getAccessibleText()).contains("Telegram"));
        fx(() -> { sync.get(0).fire(); return null; });
        Thread.sleep(400);
        assertTrue(toasts.stream().anyMatch(t -> t.contains("Telegram")), toasts.toString());
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

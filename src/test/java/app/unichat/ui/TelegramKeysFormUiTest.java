package app.unichat.ui;

import app.unichat.server.TelegramKeys;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class TelegramKeysFormUiTest {
    @BeforeAll
    static void toolkit() throws Exception {
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
            up.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyStarted) {
            // another UI test started the toolkit
        }
    }

    @Test
    void wrongKeysStayOnTheFormAndRightOnesAreSaved() throws Exception {
        TelegramKeys.save("", "");
        boolean[] saved = {false};
        TelegramKeysForm form = fx(() -> {
            TelegramKeysForm f = new TelegramKeysForm("Guardar", () -> saved[0] = true);
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(f, 500, 500));
            stage.show();
            return f;
        });
        fx(() -> {
            ((TextField) form.lookup(".telegram-api-id")).setText("no es número");
            form.save.fire();
            return null;
        });
        assertFalse(saved[0]);
        assertFalse(TelegramKeys.saved());
        assertEquals(TelegramKeys.problem("no es número", ""), fx(() -> ((Label) form.lookup(".login-error")).getText()));

        fx(() -> {
            ((TextField) form.lookup(".telegram-api-id")).setText(" 1234567 ");
            ((TextField) form.lookup(".telegram-api-hash")).setText("0123456789abcdef0123456789abcdef");
            form.save.fire();
            return null;
        });
        assertTrue(saved[0]);
        assertTrue(TelegramKeys.saved());
        assertEquals("1234567", TelegramKeys.apiId());
        TelegramKeys.save("", "");
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

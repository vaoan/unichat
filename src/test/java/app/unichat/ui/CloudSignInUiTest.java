package app.unichat.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sign-in left waiting in the browser ends when Ajustes → Nube goes: it held the port the next "Connect" needs. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class CloudSignInUiTest {
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

    @Test
    void closingTheWindowCancelsIt() throws Exception {
        CompletableFuture<Object> login = new CompletableFuture<>();
        Stage stage = FxWait.fx(() -> {
            Button connect = new Button("Conectar");
            CloudPage.cancelWhenGone(connect, () -> login);
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(new StackPane(connect), 200, 100));
            s.show();
            return s;
        });
        assertFalse(login.isDone(), "still waiting while the page shows");
        FxWait.fx(() -> { stage.close(); return null; });
        assertTrue(login.isCancelled());
    }

    @Test
    void leavingThePageCancelsIt() throws Exception {
        CompletableFuture<Object> login = new CompletableFuture<>();
        StackPane[] root = {null};
        Stage stage = FxWait.fx(() -> {
            Button connect = new Button("Conectar");
            CloudPage.cancelWhenGone(connect, () -> login);
            root[0] = new StackPane(connect);
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(root[0], 200, 100));
            s.show();
            return s;
        });
        FxWait.fx(() -> { root[0].getChildren().clear(); return null; }); // another page of Ajustes
        assertTrue(login.isCancelled());
        FxWait.fx(() -> { stage.close(); return null; });
    }
}

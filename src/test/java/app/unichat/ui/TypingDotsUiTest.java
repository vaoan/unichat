package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class TypingDotsUiTest {
    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c);
        Platform.runLater(t);
        return t.get(20, TimeUnit.SECONDS);
    }

    @Test
    void theDotsMoveOnlyWhileTheyreOnScreen() throws Exception {
        TypingDots dots = fx(TypingDots::new);
        assertFalse(fx(dots::isRunning), "not shown yet");
        StackPane root = fx(StackPane::new);
        Stage stage = fx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(root, 200, 100));
            s.show();
            root.getChildren().add(dots);
            return s;
        });
        assertTrue(fx(dots::isRunning));
        Set<Double> seen = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            seen.add(fx(() -> dots.getChildren().get(0).getOpacity()));
            Thread.sleep(80);
        }
        assertTrue(seen.size() > 1, "the first dot changes: " + seen);
        fx(() -> root.getChildren().remove(dots));
        assertFalse(fx(dots::isRunning), "nothing keeps ticking once it's gone");
        fx(() -> { stage.close(); return null; });
    }

    @Test
    void eachDotPeaksInTurn() {
        assertEquals(TypingDots.BRIGHT, TypingDots.opacity(0, 0));
        assertEquals(TypingDots.BRIGHT, TypingDots.opacity(1, 1));
        assertEquals(TypingDots.BRIGHT, TypingDots.opacity(2, 2));
        assertEquals(TypingDots.DIM, TypingDots.opacity(1, 0));
        assertEquals(TypingDots.DIM, TypingDots.opacity(0, 3), "a pause with all of them dim");
    }

    @Test
    void theOpenChatShowsThemAndTheyGoWhenNobodyTypes() throws Exception {
        MockChatService svc = new MockChatService();
        Stage[] stage = new Stage[1];
        MainView view = fx(() -> {
            stage[0] = new Stage();
            MainView v = new MainView(svc, stage[0], new Notifier(() -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(v, 1100, 760);
            Theme.register(sc);
            stage[0].setScene(sc);
            stage[0].show();
            v.openChatId("!familia:demo");
            return v;
        });
        Chat chat = svc.chat("!familia:demo");
        fx(() -> { chat.typing().add("Laura"); view.thread().refreshHeader(); view.list().refresh(); return null; });
        Thread.sleep(300);
        assertNotNull(fx(() -> view.lookup(".chat-preview.typing .typing-dots")), "the chat's row in the list too");
        Label line = fx(() -> (Label) view.lookup(".typing-line"));
        assertNotNull(fx(line::getGraphic));
        assertEquals("Laura está escribiendo", fx(line::getText));
        assertTrue(fx(line::getAccessibleText).contains("escribiendo"));
        fx(() -> {
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(view.getScene().snapshot(null), null), "png",
                    java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "unichat-typing.png").toFile());
            return null;
        });
        fx(() -> { chat.typing().clear(); view.thread().refreshHeader(); view.list().refresh(); return null; });
        Thread.sleep(300);
        assertNull(fx(line::getGraphic));
        assertNull(fx(() -> view.lookup(".chat-list .typing-dots")), "a recycled row doesn't keep them");
        fx(() -> { stage[0].close(); svc.stop(); return null; });
    }
}

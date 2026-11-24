package app.unichat.ui.timeline;

import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The footer changed twice: an older capture finishing last never replaces the newer one. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class CaptureRetakeUiTest {
    private javafx.stage.Stage stage;

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

    @AfterEach
    void close() throws Exception {
        FxWait.onFx(() -> {
            if (stage != null) stage.close();
        });
    }

    private static ConversationShot.Shot shot(int height) {
        return new ConversationShot.Shot(new WritableImage(840, height), List.of(height));
    }

    @Test
    void theNewestTakeWinsWhateverFinishesLast() throws Exception {
        MockChatService svc = new MockChatService();
        StackPane overlay = new StackPane();
        List<CompletableFuture<ConversationShot.Shot>> takes = new CopyOnWriteArrayList<>();
        CaptureSheet sheet = new CaptureSheet(svc, n -> overlay.getChildren().setAll(n), () -> overlay.getChildren().clear(), t -> { });
        FxWait.fx(() -> {
            stage = new javafx.stage.Stage();
            stage.setOpacity(0);
            stage.setScene(new javafx.scene.Scene(overlay, 600, 700));
            stage.show();
            sheet.open(3, svc.chat("!family:demo"), footer -> {
                CompletableFuture<ConversationShot.Shot> f = new CompletableFuture<>();
                takes.add(f);
                return f;
            });
            return null;
        });
        FxWait.until("the sheet is drawn", () -> FxWait.fx(() -> overlay.lookup(".check-box") != null)); // a scroll pane's content comes with its skin
        CheckBox footer = FxWait.fx(() -> (CheckBox) overlay.lookup(".check-box"));
        FxWait.onFx(footer::fire);  // a second take, while the first is still being made
        assertEquals(2, takes.size());
        takes.get(1).complete(shot(500)); // the newer one first
        takes.get(0).complete(shot(900)); // then the older
        FxWait.settled();
        double shown = FxWait.fx(() -> {
            Node v = overlay.lookup(".capture-picture");
            return ((ImageView) v).getImage().getHeight();
        });
        assertEquals(500 * 380 / 840, (int) shown, "the newer one stays");
        svc.stop();
    }

    @Test
    void aCaptureRefusedSaysWhyAndOffersNothingToSend() throws Exception {
        MockChatService svc = new MockChatService();
        StackPane overlay = new StackPane();
        CaptureSheet sheet = new CaptureSheet(svc, n -> overlay.getChildren().setAll(n), () -> overlay.getChildren().clear(), t -> { });
        FxWait.fx(() -> {
            stage = new javafx.stage.Stage();
            stage.setOpacity(0);
            stage.setScene(new javafx.scene.Scene(overlay, 600, 700));
            stage.show();
            sheet.open(500, svc.chat("!family:demo"), footer -> CompletableFuture.failedFuture(new ConversationShot.TooLong()));
            return null;
        });
        FxWait.until("the sheet is drawn", () -> FxWait.fx(() -> overlay.lookup(".check-box") != null));
        FxWait.settled();
        List<String> said = FxWait.fx(() -> overlay.lookupAll(".label").stream().map(n -> ((javafx.scene.control.Label) n).getText()).toList());
        org.junit.jupiter.api.Assertions.assertTrue(said.stream().anyMatch(t -> t.contains("demasiado largo")), said.toString());
        org.junit.jupiter.api.Assertions.assertTrue(FxWait.fx(() -> overlay.lookupAll(".button").stream()
                .filter(b -> List.of("Enviar a un chat…", "Copiar", "Guardar…").contains(((javafx.scene.control.Button) b).getText()))
                .allMatch(Node::isDisabled)), "nothing to send");
        svc.stop();
    }
}

package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chat's profile turns "save everything they send" on and off, and says where it goes. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AutoSaveUiTest {
    @Test
    void theProfileTurnsItOnAndSaysWhere() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        InfoPane pane = FxWait.fx(() -> {
            InfoPane p = new InfoPane(svc, () -> { }, t -> { });
            Stage stage = new Stage();
            stage.setOpacity(0);
            Scene sc = new Scene(p, 360, 900);
            sc.getStylesheets().add(AutoSaveUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
            sc.getRoot().setStyle("-fx-font-size: 21px;"); // 160 % text
            stage.setScene(sc);
            stage.show();
            p.show(svc.chat("!familia:demo"));
            return p;
        });
        FxWait.until("the option shows", () -> pane.lookup(".info-autosave") != null);
        CheckBox box = FxWait.fx(() -> (CheckBox) pane.lookup(".info-autosave .check-box"));
        assertFalse(FxWait.fx(box::isSelected), "off at first");
        String help = FxWait.fx(box::getAccessibleHelp);
        assertTrue(help.contains("test-autosave") && help.contains("Familia"), "says where (never the person's Downloads in a test): " + help);
        FxWait.fx(() -> { box.fire(); return null; });
        assertTrue(svc.autoSaves("!familia:demo"), "on, kept for the chat");
        FxWait.until("the folder can be opened", () -> pane.lookupAll(".info-autosave .button").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Button b && b.getText().equals("Abrir carpeta")));
        String shot = System.getProperty("unichat.autosaveShot"); // -Dunichat.autosaveShot=<png>: how it looks
        if (shot != null) {
            FxWait.fx(() -> { pane.setVvalue(1); return null; });
            FxWait.settled();
            var img = FxWait.fx(() -> pane.snapshot(null, null));
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(img, null), "png", new java.io.File(shot));
        }
        FxWait.fx(() -> { pane.getScene().getWindow().hide(); return null; });
        svc.stop();
    }
}

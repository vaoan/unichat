package app.unichat.ui.settings;

import app.unichat.assist.AiSettings;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static app.unichat.ui.FxWait.fx;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ajustes → Escribir con IA → "Corregir cada mensaje que envío": off at first, on and off from its switch. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AutoFixSettingUiTest {
    private Stage stage;

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
        fx(() -> {
            if (stage != null) stage.close();
            return null;
        });
        AiSettings.setAutoFix(false);
    }

    @Test
    void itsSwitchTurnsCorrectingEveryMessageOnAndOff() throws Exception {
        assertFalse(AiSettings.autoFix(), "off unless chosen");
        Parent root = fx(() -> {
            stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(new StackPane(AiReplyPage.build()), 700, 900));
            stage.show();
            return stage.getScene().getRoot();
        });
        CheckBox on = fx(() -> root.lookupAll(".switch").stream().map(n -> (CheckBox) n)
                .filter(c -> c.getAccessibleText() != null && c.getAccessibleText().startsWith("Corregir cada mensaje que envío"))
                .findFirst().orElseThrow());
        assertFalse(fx(on::isSelected));
        fx(() -> { on.fire(); return null; });
        assertTrue(AiSettings.autoFix());
        fx(() -> { on.fire(); return null; });
        assertFalse(AiSettings.autoFix());
    }
}

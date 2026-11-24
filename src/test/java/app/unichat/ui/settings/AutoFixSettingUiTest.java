package app.unichat.ui.settings;

import app.unichat.assist.AiSettings;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static app.unichat.ui.FxWait.fx;
import static app.unichat.ui.FxWait.onFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ajustes → Escribir con IA → "Corregir lo que envío" (off, after sending, before sending) and "Responder al mensaje
 * correcto si la conversación avanzó", each set from its control.
 */
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
        AiSettings.setFixMode(AiSettings.FixMode.OFF);
        AiSettings.setKeepThread(true);
    }

    private Parent page() throws InterruptedException {
        return fx(() -> {
            stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(new StackPane(AiReplyPage.build()), 700, 900));
            stage.show();
            return stage.getScene().getRoot();
        });
    }

    private static ToggleButton chip(Parent root, String text) throws InterruptedException {
        return fx(() -> root.lookupAll(".toggle-button").stream().map(n -> (ToggleButton) n)
                .filter(b -> text.equals(b.getText())).findFirst().orElseThrow());
    }

    @Test
    void whenWhatsSentIsCorrectedIsChosenAmongThree() throws Exception {
        assertEquals(AiSettings.FixMode.OFF, AiSettings.fixMode(), "off unless chosen");
        Parent root = page();
        assertTrue(fx(() -> chip(root, "Apagado").isSelected()));
        onFx(() -> chip(root, "Antes de enviar").fire());
        assertEquals(AiSettings.FixMode.BEFORE, AiSettings.fixMode());
        onFx(() -> chip(root, "Después de enviar").fire());
        assertEquals(AiSettings.FixMode.AFTER, AiSettings.fixMode());
        onFx(() -> chip(root, "Apagado").fire());
        assertEquals(AiSettings.FixMode.OFF, AiSettings.fixMode());
    }

    @Test
    void answeringTheRightMessageTurnsOnAndOff() throws Exception {
        assertTrue(AiSettings.keepThread(), "on unless turned off");
        Parent root = page();
        CheckBox on = fx(() -> root.lookupAll(".switch").stream().map(n -> (CheckBox) n)
                .filter(c -> c.getAccessibleText() != null && c.getAccessibleText().startsWith("Responder al mensaje correcto"))
                .findFirst().orElseThrow());
        assertTrue(fx(on::isSelected));
        onFx(on::fire);
        assertFalse(AiSettings.keepThread());
        onFx(on::fire);
        assertTrue(AiSettings.keepThread());
    }
}

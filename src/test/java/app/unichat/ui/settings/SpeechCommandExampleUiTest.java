package app.unichat.ui.settings;

import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The own-command box shows its Whisper example with the model's path in the UI language and the English placeholders. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SpeechCommandExampleUiTest {
    @Test
    void theExampleIsInTheUiLanguage() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        String before = I18n.locale().getLanguage();
        try {
            I18n.use("en");
            assertEquals("whisper-cli -m /path/to/model.bin -f {file} -l {language} -nt -np", example());
            I18n.use("es");
            assertEquals("whisper-cli -m /ruta/al/modelo.bin -f {file} -l {language} -nt -np", example());
        } finally {
            I18n.use(before);
        }
    }

    private static String example() throws Exception {
        return FxWait.fx(() -> SpeechPage.build().lookupAll(".text-field").stream()
                .map(n -> (TextField) n).filter(t -> I18n.t("settings.speech.ownCommand").equals(t.getAccessibleText()))
                .findFirst().orElseThrow().getPromptText());
    }
}

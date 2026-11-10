package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ⌘Z after an AI draft brings back what was written before it: a draft can change what the person meant. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class DraftUndoUiTest {
    @Test
    void undoGoesBackToWhatWasWrittenBeforeTheDraft() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        Composer[] composer = new Composer[1];
        TextArea box = FxWait.fx(() -> {
            Composer c = new Composer(new MockChatService(), x -> { });
            c.setChat("!familia:demo");
            composer[0] = c;
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(c), 600, 300));
            stage.show();
            TextArea t = (TextArea) c.lookup(".text-area");
            t.replaceText(0, 0, "dile que sí pero mañana");
            return t;
        });
        FxWait.fx(() -> {
            composer[0].aiDraft("!familia:demo", "¡Claro que sí! Nos vemos mañana.");
            return null;
        });
        assertEquals("¡Claro que sí! Nos vemos mañana.", FxWait.fx(box::getText));
        FxWait.fx(() -> {
            box.undo();
            return null;
        });
        assertEquals("dile que sí pero mañana", FxWait.fx(box::getText), "⌘Z: back to what was written");
        FxWait.fx(() -> {
            box.redo();
            return null;
        });
        assertEquals("¡Claro que sí! Nos vemos mañana.", FxWait.fx(box::getText), "and ⌘⇧Z: the draft again");
        FxWait.fx(() -> { box.getScene().getWindow().hide(); return null; });
    }
}

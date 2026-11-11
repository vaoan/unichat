package app.unichat.ui.settings;

import app.unichat.assist.StyleLearner;
import app.unichat.assist.WritingStyle;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static app.unichat.ui.FxWait.fx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ajustes → Escribir con IA's "Lo último que aprendió": what changed, undoing it, and the corrections still waiting. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class LastLearnedUiTest {
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
        WritingStyle.get().forget();
    }

    private Parent page() throws Exception {
        return fx(() -> {
            stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(new StackPane(AiReplyPage.build()), 700, 900));
            stage.show();
            return stage.getScene().getRoot();
        });
    }

    private static List<String> texts(Parent root) throws Exception {
        return fx(() -> root.lookupAll(".label").stream().filter(Node::isVisible)
                .map(n -> ((Label) n).getText()).filter(t -> t != null && !t.isBlank()).toList());
    }

    private static Button undo(Parent root) throws Exception {
        return fx(() -> root.lookupAll(".button").stream().map(n -> (Button) n)
                .filter(b -> "Deshacer lo último aprendido".equals(b.getText())).findFirst().orElseThrow());
    }

    @Test
    void showsTheLastChangeAndUndoesIt() throws Exception {
        WritingStyle style = WritingStyle.get();
        style.forget();
        style.save("- frases cortas\n- ríe con jajaja");
        style.addCorrection("jajaja", "hahaha");
        new StyleLearner(style, p -> "- frases cortas\n- ríe con hahaha").learn("!c", List.of());
        style.addCorrection("nos vemos mañana", "nos vemos");

        Parent root = page();
        List<String> shown = texts(root);
        assertTrue(shown.contains("Lo último que aprendió"), shown.toString());
        assertTrue(shown.stream().anyMatch(t -> t.startsWith("Último cambio: ")), shown.toString());
        assertTrue(shown.contains("Añadió:\n• ríe con hahaha"), shown.toString());
        assertTrue(shown.contains("Quitó o cambió:\n• ríe con jajaja"), shown.toString());
        assertTrue(shown.contains("1 corrección pendiente"), shown.toString());
        assertTrue(shown.contains("Borradores que corregiste antes de enviar; se aprenden con el próximo borrador."), shown.toString());

        Button undo = undo(root);
        assertTrue(fx(() -> !undo.isDisabled() && undo.isFocusTraversable()), "reachable by keyboard");
        assertEquals("Vuelve a la versión anterior. La deshecha queda guardada en este equipo.", fx(undo::getAccessibleHelp));
        fx(() -> { undo.fire(); return null; });

        assertEquals("- frases cortas\n- ríe con jajaja", style.text());
        TextArea learned = fx(() -> root.lookupAll(".text-area").stream().map(n -> (TextArea) n)
                .filter(t -> "Lo aprendido de cómo escribes".equals(t.getAccessibleText())).findFirst().orElseThrow());
        assertEquals("- frases cortas\n- ríe con jajaja", fx(learned::getText), "the box shows what's back");
        assertTrue(fx(undo::isDisabled), "nothing more to undo");
        List<String> after = texts(root);
        assertTrue(after.contains("Aún no aprendió nada: aprende después de tus borradores."), after.toString());
        assertFalse(after.stream().anyMatch(t -> t.startsWith("Añadió:")), after.toString());
    }

    @Test
    void forgettingEmptiesTheSection() throws Exception {
        WritingStyle style = WritingStyle.get();
        style.forget();
        style.addCorrection("a", "b");
        new StyleLearner(style, p -> "- tutea").learn("!c", List.of());
        Parent root = page();
        Button forget = fx(() -> root.lookupAll(".button").stream().map(n -> (Button) n)
                .filter(b -> "Borrar lo aprendido".equals(b.getText())).findFirst().orElseThrow());
        fx(() -> { forget.fire(); return null; });
        FxWait.settled();
        List<String> shown = texts(root);
        assertTrue(shown.contains("Aún no aprendió nada: aprende después de tus borradores."), shown.toString());
        assertTrue(shown.contains("0 correcciones pendientes"), shown.toString());
        Button undo = undo(root);
        assertTrue(fx(undo::isDisabled));
    }
}

package app.unichat.ui.timeline;

import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shift+↑ / Shift+↓ in the message box: first to the start / end of the cursor's line, then one line at a time, to
 * where ↑ / ↓ alone would put the cursor, in the column the run started from. A monospaced font, so a column is a
 * character.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ComposerSelectionUiTest {
    private static final String TEXT = "primera linea\nsegunda linea larga\ntercera";
    private static final int LINE_2 = TEXT.indexOf("segunda"), LINE_3 = TEXT.indexOf("tercera");

    private Stage stage;
    private TextArea box;

    private void open(String text, double width) throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        box = FxWait.fx(() -> {
            Composer composer = new Composer(new MockChatService(), x -> { });
            composer.setChat("!familia:demo");
            stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(composer, width, 240));
            stage.show();
            TextArea b = (TextArea) composer.lookup(".text-area");
            b.setStyle("-fx-font-family: 'Monospaced';");
            b.setText(text);
            return b;
        });
        FxWait.settled(); // laid out: the lines as drawn are known
    }

    @AfterEach
    void close() throws Exception {
        if (stage != null) FxWait.fx(() -> { stage.close(); return null; });
    }

    private String shift(KeyCode arrow) throws Exception {
        return FxWait.fx(() -> {
            Event.fireEvent(box, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", arrow, true, false, false, false));
            return box.getSelectedText();
        });
    }

    private void caretAt(int at) throws Exception {
        FxWait.fx(() -> { box.positionCaret(at); return null; });
    }

    @Test
    void upTakesTheLineToItsStartThenALineAtATimeInTheSameColumn() throws Exception {
        open(TEXT, 700);
        int from = LINE_3 + 4; // "terc|era"
        caretAt(from);
        assertEquals("terc", shift(KeyCode.UP), "first: to the start of the line");
        assertEquals(TEXT.substring(LINE_2 + 4, from), shift(KeyCode.UP), "then the line above, at the same column");
        assertEquals(TEXT.substring(4, from), shift(KeyCode.UP));
        assertEquals(TEXT.substring(0, from), shift(KeyCode.UP), "no line above: to the very start");
        assertEquals(TEXT, FxWait.fx(box::getText), "only selects");
    }

    @Test
    void downTakesTheLineToItsEndThenALineAtATimeInTheSameColumn() throws Exception {
        open(TEXT, 700);
        caretAt(4); // "prim|era"
        assertEquals("era linea", shift(KeyCode.DOWN), "first: to the end of the line");
        assertEquals(TEXT.substring(4, LINE_2 + 4), shift(KeyCode.DOWN), "then the line below, at the same column");
        assertEquals(TEXT.substring(4, LINE_3 + 4), shift(KeyCode.DOWN));
        assertEquals(TEXT.substring(4), shift(KeyCode.DOWN), "no line below: to the very end");
    }

    @Test
    void atTheStartOfALineAlreadyTheFirstPressGoesUpALine() throws Exception {
        open(TEXT, 700);
        caretAt(LINE_3);
        assertEquals(TEXT.substring(LINE_2, LINE_3), shift(KeyCode.UP), "column 0 of the line above");
    }

    @Test
    void movingTheCursorOtherwiseStartsANewRun() throws Exception {
        open(TEXT, 700);
        caretAt(LINE_3 + 4);
        shift(KeyCode.UP);
        shift(KeyCode.UP);
        caretAt(LINE_2 + 2); // a click, say
        assertEquals("se", shift(KeyCode.UP), "first again: to the start of that line");
    }

    @Test
    void aLineIsALineAsDrawn() throws Exception {
        String longLine = "uno dos tres cuatro cinco seis siete ocho nueve diez once doce trece catorce quince";
        open(longLine, 260); // narrow: the text wraps over several lines
        caretAt(longLine.length());
        String first = shift(KeyCode.UP);
        assertTrue(!first.isEmpty() && first.length() < longLine.length(), "only the last line as drawn: '" + first + "'");
        String second = shift(KeyCode.UP);
        assertTrue(second.length() > first.length() && second.length() < longLine.length(), "then the one above it: '" + second + "'");
    }
}

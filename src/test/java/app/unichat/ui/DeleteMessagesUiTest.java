package app.unichat.ui;

import app.unichat.model.Message;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting for everyone: one message from its menu, or several picked in the chat, always behind two questions, the
 * safe answer focused in both. It used to delete at the first click of "Eliminar para todos".
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class DeleteMessagesUiTest {
    private TimelineWindow w;

    @BeforeAll
    static void toolkit() throws Exception { TimelineWindow.toolkit(); }

    @AfterEach
    void close() throws Exception { if (w != null) w.close(); }

    private Node sheet() throws InterruptedException {
        return FxWait.fx(() -> w.scene.getRoot().lookup(".delete-sheet"));
    }

    private String title() throws InterruptedException {
        return FxWait.fx(() -> ((Label) sheet().lookup(".sheet-title")).getText());
    }

    /** The sheet's red button: "Borrar…" first, then "Sí, borrar para todos". */
    private void danger() throws InterruptedException {
        FxWait.fx(() -> {
            ((Button) sheet().lookup(".danger-button")).fire();
            return null;
        });
    }

    private void cancel() throws InterruptedException {
        FxWait.fx(() -> {
            ((Button) sheet().lookup(".secondary-button")).fire();
            return null;
        });
    }

    private boolean cancelFocused() throws InterruptedException {
        return FxWait.fx(() -> w.scene.getFocusOwner() == sheet().lookup(".secondary-button"));
    }

    @Test
    void oneFromItsMenuIsAskedTwiceAndNothingGoesUntilTheSecondYes() throws Exception {
        w = TimelineWindow.open(3, "mensaje %d");
        Message mine = w.newest();
        FxWait.until("it's listed", () -> w.cellOf(mine) != null);

        TimelineWindow.fire(w.menuItem(mine, "Eliminar para todos…"));
        assertEquals("¿Eliminar este mensaje para todos?", title());
        assertEquals("“mensaje 2”", FxWait.fx(() -> ((Label) sheet().lookup(".delete-preview")).getText()), "what goes");
        FxWait.until("the safe answer has the focus", this::cancelFocused);
        danger();
        assertEquals("¿Seguro?", title(), "a second question");
        assertFalse(mine.deleted(), "nothing deleted after the first yes");
        FxWait.until("the safe answer has the focus again", this::cancelFocused);
        cancel();
        assertNull(sheet(), "cancelled: the sheet goes");
        assertFalse(mine.deleted());

        TimelineWindow.fire(w.menuItem(mine, "Eliminar para todos…"));
        danger();
        danger();
        FxWait.until("deleted for everyone", mine::deleted);
        assertNull(sheet());
    }

    @Test
    void severalPickedAreDeletedTogetherOnlyMineAndOnlyAfterTwoYes() throws Exception {
        w = TimelineWindow.open(4, "mensaje %d");
        w.svc.receive(CHAT, "esto lo escribió Andrés");
        List<Message> mine = FxWait.fx(() -> w.svc.messages(CHAT).stream().filter(m -> m.body().startsWith("mensaje")).toList());
        Message first = mine.get(1);
        Message last = mine.get(3);
        Message theirs = w.newest();
        FxWait.until("they're listed", () -> w.cellOf(first) != null && w.cellOf(theirs) != null);

        TimelineWindow.fire(w.menuItem(first, "Eliminar varios…"));
        Button delete = FxWait.fx(() -> (Button) w.scene.getRoot().lookup(".thread-header .selection-bar .danger-button"));
        assertNotNull(delete, "the bar deletes, in red");
        assertEquals("Eliminar para todos", FxWait.fx(delete::getText));
        FxWait.fx(() -> {
            TimelineWindow.press(w.cellOf(theirs), false); // someone else's can't be deleted for everyone: not picked
            TimelineWindow.press(w.cellOf(last), true);    // with Shift, mine from the first to the last
            return null;
        });
        assertEquals("3 seleccionados", FxWait.fx(() -> ((Label) w.scene.getRoot().lookup(".selection-count")).getText()));
        FxWait.fx(() -> {
            delete.fire();
            return null;
        });
        assertEquals("¿Eliminar 3 mensajes para todos?", title());
        assertEquals("“mensaje 1” y 2 más", FxWait.fx(() -> ((Label) sheet().lookup(".delete-preview")).getText()));
        danger();
        assertEquals("¿Seguro?", title());
        assertTrue(mine.stream().noneMatch(Message::deleted), "nothing deleted after the first yes");
        danger();
        FxWait.until("the three deleted", () -> mine.subList(1, 4).stream().allMatch(Message::deleted));
        assertFalse(mine.get(0).deleted(), "the one not picked stays");
        assertFalse(theirs.deleted(), "and someone else's");
        FxWait.until("picking ended", () -> w.scene.getRoot().lookup(".selection-bar") == null);
    }
}

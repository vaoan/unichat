package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.ui.timeline.ThreadRows;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forwarding from the screen: one message from its menu, or several picked in the chat, to chats of any network; the
 * sheet says what the copy will carry about where it came from, nothing unless asked.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ForwardUiTest {
    private static final String TO = "!familia:demo";
    private TimelineWindow w;

    @BeforeAll
    static void toolkit() throws Exception { TimelineWindow.toolkit(); }

    @AfterEach
    void close() throws Exception { if (w != null) w.close(); }

    @Test
    void oneMessageGoesFromItsMenuSayingWhoOnlyWhenAsked() throws Exception {
        w = TimelineWindow.open(3, "mensaje %d");
        w.svc.receive(CHAT, "nos vemos a las 8");
        Message theirs = w.newest();
        FxWait.until("it's listed", () -> cellOf(theirs) != null);
        fire(menuItem(theirs, "Reenviar…"));
        assertEquals("1 seleccionado", FxWait.fx(() -> ((Label) w.scene.getRoot().lookup(".selection-count")).getText()),
                "Reenviar starts picking with it");
        forwardPicked();
        Node sheet = FxWait.fx(() -> w.scene.getRoot().lookup(".forward-sheet"));
        assertNotNull(sheet, "the sheet asks where");
        assertEquals("Reenviar 1 mensaje", FxWait.fx(() -> ((Label) sheet.lookup(".sheet-title")).getText()));
        assertEquals("Irá tal cual, sin decir de dónde viene.", FxWait.fx(() -> ((Label) sheet.lookup(".forward-preview")).getText()),
                "by default a forward says nothing");
        assertTrue(FxWait.fx(() -> ((Button) sheet.lookup(".primary-button")).isDisabled()), "no chat chosen yet");

        choose(sheet, "Familia");
        FxWait.fx(() -> {
            checkBox(sheet, "Quién lo escribió").setSelected(true);
            return null;
        });
        String line = "↪ Reenviado de " + theirs.senderName();
        assertEquals("Antes irá: " + line, FxWait.fx(() -> ((Label) sheet.lookup(".forward-preview")).getText()));
        int before = w.svc.messages(TO).size();
        FxWait.fx(() -> {
            ((Button) sheet.lookup(".primary-button")).fire();
            return null;
        });
        FxWait.until("the copy arrives", () -> w.svc.messages(TO).size() == before + 2);
        List<Message> sent = w.svc.messages(TO).subList(before, before + 2);
        assertEquals(List.of(line, "nos vemos a las 8"), sent.stream().map(Message::body).toList());
        assertTrue(sent.stream().allMatch(Message::outgoing));
    }

    @Test
    void severalPickedInTheChatGoInItsOrder() throws Exception {
        w = TimelineWindow.open(5, "mensaje %d");
        List<Message> mine = FxWait.fx(() -> w.svc.messages(CHAT).stream().filter(m -> m.body().startsWith("mensaje")).toList());
        Message first = mine.get(1), third = mine.get(3), last = mine.get(4);
        FxWait.until("they're listed", () -> cellOf(first) != null && cellOf(last) != null);
        fire(menuItem(first, "Reenviar…"));
        assertTrue(FxWait.fx(() -> w.scene.getRoot().lookup(".thread-header .selection-bar") != null), "the bar is in the header");
        assertTrue(FxWait.fx(() -> w.scene.getRoot().lookup(".thread-header").lookupAll(".icon-button").isEmpty()),
                "in the place of the star and the other buttons");
        assertNotNull(FxWait.fx(() -> w.scene.getRoot().lookup(".composer")), "the message box stays");
        FxWait.fx(() -> {
            press(cellOf(last), false);  // picks the newest
            press(cellOf(third), true);  // and with Shift, what's between them
            return null;
        });
        assertEquals("3 seleccionados", FxWait.fx(() -> ((Label) w.scene.getRoot().lookup(".selection-count")).getText()));
        assertTrue(FxWait.fx(() -> cellOf(third).getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("picked"))),
                "a picked message looks picked");
        forwardPicked();
        Node sheet = FxWait.fx(() -> w.scene.getRoot().lookup(".forward-sheet"));
        assertEquals("Reenviar 3 mensajes", FxWait.fx(() -> ((Label) sheet.lookup(".sheet-title")).getText()));
        choose(sheet, "Familia");
        int before = w.svc.messages(TO).size();
        FxWait.fx(() -> {
            ((Button) sheet.lookup(".primary-button")).fire();
            return null;
        });
        FxWait.until("all three arrive", () -> w.svc.messages(TO).size() == before + 3);
        FxWait.until("the bar on top says how it went", () -> {
            Node bar = w.scene.getRoot().lookup(".forward-bar");
            return bar != null && bar.isVisible() && ((Label) bar.lookup(".audio-who")).getText().equals("3 reenviados a Familia 🏡")
                    && ((Label) bar.lookup(".upload-percent")).getText().equals("3 de 3");
        });
        assertEquals(List.of(first.body(), third.body(), last.body()),
                w.svc.messages(TO).subList(before, before + 3).stream().map(Message::body).toList(), "in the chat's order, nothing added");
        FxWait.until("picking ended, the header's buttons are back", () -> w.scene.getRoot().lookup(".selection-bar") == null);
        assertFalse(FxWait.fx(() -> cellOf(third).getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("picked"))));
    }

    /**
     * While picking, a key acts on nothing but the picking: R answered and E edited in the hidden message box, which
     * showed it when picking ended. And a message deleted meanwhile no longer counts nor goes.
     */
    @Test
    void whilePickingKeysDontActOnMessagesAndADeletedOneDropsOut() throws Exception {
        w = TimelineWindow.open(4, "mensaje %d");
        List<Message> mine = FxWait.fx(() -> w.svc.messages(CHAT).stream().filter(m -> m.body().startsWith("mensaje")).toList());
        Message a = mine.get(2), b = mine.get(3);
        FxWait.until("they're listed", () -> cellOf(a) != null && cellOf(b) != null);
        fire(menuItem(a, "Reenviar…"));
        FxWait.fx(() -> {
            press(cellOf(b), false);
            for (KeyCode k : List.of(KeyCode.R, KeyCode.E, KeyCode.DIGIT1)) {
                w.list().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", k, false, false, false, false));
            }
            return null;
        });
        assertEquals("2 seleccionados", FxWait.fx(() -> ((Label) w.scene.getRoot().lookup(".selection-count")).getText()));
        assertTrue(FxWait.fx(() -> b.reactions().isEmpty()), "1 doesn't react while picking");
        w.svc.deleteMessage(CHAT, a.id()).get();
        FxWait.until("the deleted one drops out", () -> "1 seleccionado".equals(((Label) w.scene.getRoot().lookup(".selection-count")).getText()));
        FxWait.fx(() -> {
            w.list().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            return null;
        });
        FxWait.until("picking ended", () -> w.scene.getRoot().lookup(".selection-bar") == null);
        assertFalse(FxWait.fx(() -> w.scene.getRoot().lookup(".composer-banner").isVisible()), "R or E while picking started no answer or edit");
    }

    /** After forwarding, nothing looks picked: rows scrolled away while picking kept the look when they came back. */
    @Test
    void afterForwardingNoRowLooksPickedWhereverItIs() throws Exception {
        w = TimelineWindow.open(30);
        List<Message> mine = FxWait.fx(() -> w.svc.messages(CHAT).stream().filter(m -> m.body().startsWith("Mensaje")).toList());
        Message newest = mine.getLast();
        FxWait.until("listed", () -> cellOf(newest) != null);
        fire(menuItem(newest, "Reenviar…"));
        // Up through the chat, picking what shows on the way.
        for (int i = 0; i < 6; i++) {
            w.wheel(400);
            FxWait.settled();
            FxWait.fx(() -> {
                for (Node n : w.list().lookupAll(".row-cell")) {
                    if (n instanceof ListCell<?> c && c.isVisible() && c.getItem() instanceof ThreadRows.MessageRow mr
                            && mr.message().body().startsWith("Mensaje") && c.getIndex() % 3 == 0) {
                        press(c, false);
                        break;
                    }
                }
                return null;
            });
        }
        forwardPicked();
        Node sheet = FxWait.fx(() -> w.scene.getRoot().lookup(".forward-sheet"));
        choose(sheet, "Familia");
        FxWait.fx(() -> {
            ((Button) sheet.lookup(".primary-button")).fire();
            return null;
        });
        FxWait.until("picking ended", () -> w.scene.getRoot().lookup(".selection-bar") == null);
        // Every row, drawn again wherever it is.
        for (double at : new double[] {0, 0.25, 0.5, 0.75, 1}) {
            w.dragScrollbar(at);
            FxWait.settled();
            List<String> stillPicked = FxWait.fx(() -> w.list().lookupAll(".row-cell").stream()
                    .filter(n -> n.getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("picked")))
                    .map(n -> String.valueOf(((ListCell<?>) n).getIndex())).toList());
            assertTrue(stillPicked.isEmpty(), "at " + at + " rows still look picked: " + stillPicked);
        }
    }

    /** {@code m}'s row on screen, or null. */
    private ListCell<?> cellOf(Message m) {
        for (Node n : w.list().lookupAll(".row-cell")) {
            if (n instanceof ListCell<?> c && c.getItem() instanceof ThreadRows.MessageRow mr && mr.message().id().equals(m.id())) return c;
        }
        return null;
    }

    /** The item {@code text} of {@code m}'s menu, opened from the keys as a person would (Enter on it). */
    private MenuItem menuItem(Message m, String text) throws Exception {
        return FxWait.fx(() -> {
            ListCell<?> cell = cellOf(m);
            w.list().getSelectionModel().select(cell.getIndex());
            w.list().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            ContextMenu menu = Window.getWindows().stream().filter(x -> x instanceof ContextMenu cm && cm.isShowing()
                    && cm.getOwnerNode() != null && cm.getOwnerNode().getScene() == w.scene).map(x -> (ContextMenu) x).findFirst().orElseThrow();
            MenuItem item = menu.getItems().stream().filter(i -> text.equals(i.getText())).findFirst().orElseThrow();
            menu.hide();
            return item;
        });
    }

    /** The header's "Reenviar", which asks where the picked ones go. */
    private void forwardPicked() throws Exception {
        FxWait.fx(() -> {
            ((Button) w.scene.getRoot().lookup(".thread-header .selection-bar .primary-button")).fire();
            return null;
        });
    }

    private static void fire(MenuItem item) throws Exception {
        FxWait.fx(() -> {
            item.fire();
            return null;
        });
    }

    private static void press(Node cell, boolean shift) {
        cell.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1, shift, false, false, false,
                true, false, false, true, false, true, null));
    }

    /** Searches {@code name} in the sheet and picks the first chat found, with the keys. */
    private static void choose(Node sheet, String name) throws Exception {
        FxWait.fx(() -> {
            TextField search = (TextField) sheet.lookup(".text-field");
            search.setText(name);
            search.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        assertFalse(FxWait.fx(() -> ((Button) sheet.lookup(".primary-button")).isDisabled()), "a chat is chosen");
    }

    private static CheckBox checkBox(Node sheet, String text) {
        return sheet.lookupAll(".check-box").stream().map(n -> (CheckBox) n).filter(c -> text.equals(c.getText())).findFirst().orElseThrow();
    }
}

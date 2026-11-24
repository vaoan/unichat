package app.unichat.ui;

import app.unichat.Present;
import app.unichat.autosave.AutoSave;
import app.unichat.model.Message;
import app.unichat.model.SaveKind;
import javafx.scene.Node;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.EnumSet;
import java.util.List;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A message deleted after it arrived can still be read from the copy of the chat's texts ("Ver lo que decía…" in its
 * menu, all a deleted message has left); one never kept says there's no copy.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SavedTextUiTest {
    @SuppressWarnings("NullAway.Init") // each test sets it before use
    private TimelineWindow w;

    @BeforeAll
    static void toolkit() throws Exception { TimelineWindow.toolkit(); }

    @AfterEach
    void close() throws Exception { if (w != null) w.close(); }

    private List<String> sheetTexts() throws InterruptedException {
        return FxWait.fx(() -> {
            Node sheet = w.scene.getRoot().lookup(".saved-text-sheet");
            return sheet == null ? List.<String>of()
                    : sheet.lookupAll(".label").stream().map(n -> ((Label) n).getText()).toList();
        });
    }

    @Test
    void aDeletedMessageShowsWhatItSaidOrThatTheresNoCopy() throws Exception {
        w = TimelineWindow.open(2, "mensaje %d");
        Message never = w.newest();
        AutoSave.of(w.svc); // listening, as the app starts it with the service
        w.svc.setAutoSave(CHAT, EnumSet.of(SaveKind.TEXTS)).get();
        w.svc.receive(CHAT, "mañana no puedo ir");
        Message said = w.newest();
        FxWait.until("it's listed", () -> w.cellOf(said) != null);
        w.svc.rewrite(CHAT, said.id(), Message::markDeleted); // deleted by its sender
        FxWait.until("shown as deleted", said::deleted);

        TimelineWindow.fire(w.menuItem(said, "Ver lo que decía…"));
        FxWait.until("what it said", () -> sheetTexts().contains("mañana no puedo ir"));

        w.svc.rewrite(CHAT, never.id(), Message::markDeleted); // arrived before the chat kept its texts
        FxWait.until("shown as deleted", never::deleted);
        TimelineWindow.fire(w.menuItem(never, "Ver lo que decía…"));
        FxWait.until("no copy", () -> sheetTexts().stream().anyMatch(t -> t.startsWith("No hay copia de este mensaje")));
        assertTrue(sheetTexts().contains("Lo que decía"));
    }

    @Test
    void anEditedMessageShowsEachVersionTheLaterOnesMarked() throws Exception {
        w = TimelineWindow.open(1, "mensaje %d");
        AutoSave.of(w.svc);
        w.svc.setAutoSave(CHAT, EnumSet.of(SaveKind.TEXTS)).get();
        w.svc.receive(CHAT, "mañana no puedo");
        Message said = w.newest();
        FxWait.until("it's listed", () -> w.cellOf(said) != null);
        w.svc.rewrite(CHAT, said.id(), m -> {
            m.setBody("mañana sí puedo");
            m.setEdited(true);
        });
        FxWait.until("edited", said::edited);
        TimelineWindow.fire(w.menuItem(said, "Ver versiones…"));
        FxWait.until("both versions", () -> sheetTexts().containsAll(List.of("mañana no puedo", "mañana sí puedo")));
        List<String> texts = sheetTexts();
        assertTrue(texts.indexOf("mañana no puedo") < texts.indexOf("mañana sí puedo"), "oldest first: " + texts);
        assertEquals(1, texts.stream().filter(t -> t.endsWith(" · editado")).count(), "the later one marked: " + texts);
    }

    @Test
    void aDeletedMessageKeepsItsMenuButtonForTheMouse() throws Exception {
        w = TimelineWindow.open(2, "mensaje %d");
        Message m = w.newest();
        FxWait.until("it's listed", () -> w.cellOf(m) != null);
        w.svc.rewrite(CHAT, m.id(), Message::markDeleted);
        FxWait.until("shown as deleted", () -> FxWait.fx(() -> w.cellOf(m) != null
                && Present.of(w.cellOf(m)).lookup(".deleted-text") != null));
        List<String> buttons = FxWait.fx(() -> Present.of(w.cellOf(m)).lookupAll(".hover-actions .button").stream()
                .map(Node::getAccessibleText).toList());
        assertEquals(List.of("Más"), buttons, "its menu (what it said), no reacting nor replying to what's gone");
    }
}

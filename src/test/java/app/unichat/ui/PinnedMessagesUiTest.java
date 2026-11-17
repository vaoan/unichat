package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.service.PinnedMessages;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pinned messages: always in view on a bar under the chat's header, however far back they are, and a click on it goes
 * to the message, loading the chat's history until it's there.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class PinnedMessagesUiTest {
    private TimelineWindow w;

    @BeforeAll
    static void toolkit() throws Exception { TimelineWindow.toolkit(); }

    @AfterEach
    void close() throws Exception {
        for (var p : PinnedMessages.get().of(CHAT)) PinnedMessages.get().unpin(CHAT, p.messageId());
        if (w != null) w.close();
    }

    private Node bar() throws Exception {
        return FxWait.fx(() -> w.scene.getRoot().lookup(".pinned-bar"));
    }

    private String barText() throws Exception {
        return FxWait.fx(() -> ((Label) bar().lookup(".pinned-preview")).getText());
    }

    private void clickBar() throws Exception {
        FxWait.fx(() -> {
            ((Button) bar().lookup(".pinned-go")).fire();
            return null;
        });
    }

    /** Whether {@code m} is the selected row and drawn in the list. */
    private boolean shown(Message m) throws Exception {
        return FxWait.fx(() -> w.cellOf(m) != null && w.list().getSelectionModel().getSelectedItem() instanceof app.unichat.ui.timeline.ThreadRows.MessageRow mr
                && mr.message().id().equals(m.id()));
    }

    @Test
    void pinnedFromItsMenuItStaysInViewAndTakesYouBackToIt() throws Exception {
        w = TimelineWindow.open(40, "mensaje %d");
        Message old = FxWait.fx(() -> w.svc.messages(CHAT).stream().filter(m -> "mensaje 35".equals(m.body())).findFirst().orElseThrow());
        FxWait.until("listed", () -> w.cellOf(old) != null);
        assertFalse(FxWait.fx(() -> bar().isVisible()), "nothing pinned: no bar");

        TimelineWindow.fire(w.menuItem(old, "Fijar mensaje"));
        FxWait.until("the bar shows it", () -> bar().isVisible() && barText().contains("mensaje 35"));

        w.scrollUp(); // away from it
        clickBar();
        FxWait.until("it took me to the message", () -> shown(old));

        TimelineWindow.fire(w.menuItem(old, "Desfijar mensaje"));
        FxWait.until("unpinned: no bar", () -> !bar().isVisible());
    }

    /** One pinned long ago, out of what's loaded: still on the bar, and the click loads the history until it's there. */
    @Test
    void oneFromLongAgoIsFoundLoadingTheHistory() throws Exception {
        w = TimelineWindow.open(60, "mensaje %d");
        Message first = FxWait.fx(() -> w.svc.messages(CHAT).stream().filter(m -> "mensaje 0".equals(m.body())).findFirst().orElseThrow());
        FxWait.fx(() -> {
            PinnedMessages.get().pin(CHAT, first);
            return null;
        });
        int loaded = FxWait.fx(() -> w.svc.messages(CHAT).size());
        w.svc.holdBack(CHAT, loaded - 15); // only the newest 15 are loaded: the pinned one is ~5 pages back

        FxWait.until("the bar shows it, though it isn't loaded", () -> bar().isVisible() && barText().contains("mensaje 0"));
        assertNull(FxWait.fx(() -> w.cellOf(first)));

        clickBar();
        FxWait.until("the history loaded until it, and it's shown", () -> shown(first));
    }

    @Test
    void severalPinsSayHowManyAndTheClickGoesFromOneToTheNext() throws Exception {
        w = TimelineWindow.open(20, "mensaje %d");
        List<Message> mine = FxWait.fx(() -> w.svc.messages(CHAT).stream().filter(m -> m.body().startsWith("mensaje 1")).toList());
        Message a = mine.get(1), b = mine.get(4); // "mensaje 10", "mensaje 13"
        FxWait.fx(() -> {
            PinnedMessages.get().pin(CHAT, a);
            PinnedMessages.get().pin(CHAT, b);
            return null;
        });
        FxWait.until("the newest pin first, and how many", () -> barText().contains("mensaje 13")
                && ((Label) bar().lookup(".pinned-count")).getText().equals("1 de 2"));
        clickBar();
        FxWait.until("to the newest pin", () -> shown(b));
        FxWait.until("the bar moves on to the next", () -> barText().contains("mensaje 10")
                && ((Label) bar().lookup(".pinned-count")).getText().equals("2 de 2"));
        clickBar();
        FxWait.until("to the next", () -> shown(a));
        FxWait.until("and round again to the first", () -> barText().contains("mensaje 13"));
        FxWait.fx(() -> {
            ((Button) bar().lookup(".pinned-unpin")).fire(); // the one on the bar
            return null;
        });
        assertFalse(FxWait.fx(() -> PinnedMessages.get().isPinned(CHAT, b.id())), "the ✕ unpins the one shown");
        assertTrue(FxWait.fx(() -> PinnedMessages.get().isPinned(CHAT, a.id())));
        FxWait.until("the other stays on the bar, alone", () -> barText().contains("mensaje 10") && !bar().lookup(".pinned-count").isVisible());
    }
}

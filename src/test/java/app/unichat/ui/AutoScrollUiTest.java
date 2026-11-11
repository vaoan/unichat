package app.unichat.ui;

import javafx.scene.Node;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The open chat at its newest message on opening and as pictures load, and the jump to a quoted message. Frame by frame
 * cases (writing, sending, arriving, reading older ones): {@link TimelineFollowUiTest}.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AutoScrollUiTest {
    private TimelineWindow w;

    @AfterEach
    void close() throws Exception {
        if (w != null) w.close();
    }

    @Test
    void clickingTheQuoteOfAReplyGoesToTheOriginalAndStaysThere() throws Exception {
        w = TimelineWindow.open(12);
        String firstId = FxWait.fx(() -> w.svc.messages(CHAT).getFirst().id());
        w.svc.sendText(CHAT, "te respondo", firstId).get();
        FxWait.until("the sent message shows", w::showsNewest);
        w.untilAtBottom();
        FxWait.fx(() -> {
            // The demo chat has replies of its own: this one's is the lowest on screen.
            Node quote = w.list().lookupAll(".reply-quote").stream()
                    .max(java.util.Comparator.comparingDouble(n -> n.localToScene(n.getBoundsInLocal()).getMinY())).orElseThrow();
            TimelineWindow.click(quote);
            return null;
        });
        FxWait.until("the original is on screen", () -> w.yOf(firstId) != null);
        FxWait.settled();
        assertTrue(FxWait.fx(w::newestGap) == null, "it stayed up");
        assertNotNull(FxWait.fx(() -> w.yOf(firstId)), "the original is on screen");
        assertTrue(FxWait.fx(w::jumpDownShown), "a way back down");
    }

    @Test
    void openingAShortChatEndsAtTheNewestMessage() throws Exception {
        w = TimelineWindow.open(12); // fewer than 20 messages: opening it also loads older ones
        assertTrue(FxWait.fx(() -> w.list().lookupAll(".scroll-bar").stream()
                .anyMatch(n -> n instanceof ScrollBar sb && sb.isVisible() && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL)),
                "precondition: the chat is taller than the window");
        w.untilAtBottom();
    }

    /**
     * A chat opened before its embed's picture loaded (Discord's embeds, link previews: HTML pictures, size unknown):
     * the picture took its shape after the chat was at its newest message, and what it added stayed below the view.
     */
    @Test
    void anEmbedsPictureLoadingAfterTheChatOpenedKeepsTheBottomInView() throws Exception {
        w = TimelineWindow.open(6, "Mensaje %d\nsegunda línea", svc -> {
            try {
                svc.sendText(CHAT, "mira esto", null).get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            svc.messages(CHAT).getLast().setHtml("<p>mira esto</p><img src=\"mxc://demo/embed-" + System.nanoTime() + "\">");
        });
        FxWait.until("the embed's picture loaded", () -> w.list().lookupAll("ImageView").stream()
                .anyMatch(n -> n instanceof javafx.scene.image.ImageView v && v.getImage() != null));
        w.untilAtBottom();
    }

    /**
     * The newest message grew taller than the window (a long embed loading): following the bottom showed its top and
     * left the rest below. Its end is what's in view.
     */
    @Test
    void aNewestMessageTallerThanTheWindowShowsItsEnd() throws Exception {
        w = TimelineWindow.open(12);
        Region grown = FxWait.fx(() -> {
            Region r = (Region) w.flow().getLastVisibleCell().getGraphic();
            r.setMinHeight(w.flow().getHeight() + 400);
            return r;
        });
        FxWait.until("the bubble is laid out taller than the window", () -> grown.getHeight() >= grown.getMinHeight());
        w.untilAtBottom();
    }

    /**
     * Reading older messages, a picture above loads and grows: what's being read stays where it was, as chat apps anchor on
     * the message in view and make up for the height that came above it.
     */
    @Test
    void readingOlderMessagesAPictureGrowingAboveDoesntMoveThem() throws Exception {
        w = TimelineWindow.open(30);
        for (int i = 0; i < 10; i++) w.wheel(120);
        FxWait.until("the list stops following the bottom", w::jumpDownShown);
        FxWait.settled();
        // The first cell wholly in view is what's being read; the one above it grows.
        Object[] read = FxWait.fx(() -> {
            VirtualFlow<?> flow = w.flow();
            IndexedCell<?> above = flow.getFirstVisibleCell();
            IndexedCell<?> reading = flow.getVisibleCell(above.getIndex() + 1);
            ((Region) above.getGraphic()).setMinHeight(above.getHeight() + 200);
            return new Object[] {reading.getItem(), reading.localToScene(0, 0).getY()};
        });
        FxWait.settled();
        double now = FxWait.fx(() -> {
            VirtualFlow<?> flow = w.flow();
            for (int i = flow.getFirstVisibleCell().getIndex(); i <= flow.getLastVisibleCell().getIndex(); i++) {
                IndexedCell<?> c = flow.getVisibleCell(i);
                if (c != null && c.getItem() == read[0]) return c.localToScene(0, 0).getY();
            }
            return Double.NaN;
        });
        assertEquals((double) read[1], now, 1, "the message being read stayed put");
    }
}

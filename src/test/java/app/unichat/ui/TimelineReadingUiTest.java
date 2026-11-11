package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.service.ScriptedChatService;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading older messages, frame by frame: nothing but the person moves what's being read (messages arriving below,
 * history loading above, the box, the window, the text size, edits and reactions around it); and every way back down
 * follows the newest message again. At the newest message: {@link TimelineFollowUiTest}.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class TimelineReadingUiTest {
    private TimelineWindow w;

    @AfterEach
    void close() throws Exception {
        if (w != null) w.close();
    }

    private TimelineWindow.Frames readingOlder() throws Exception {
        w = TimelineWindow.open(30);
        return w.readingOlder();
    }

    /** The messages in view now, but not the newest: one above the one read and one below it. */
    private Message[] around() throws Exception {
        String read = FxWait.fx(w::readMessage);
        List<Message> all = FxWait.fx(() -> w.svc.messages(CHAT));
        for (int i = 1; i < all.size() - 1; i++) {
            if (all.get(i).id().equals(read)) return new Message[] {all.get(i - 1), all.get(i + 1)};
        }
        throw new AssertionError("the message read isn't in the chat");
    }

    // ---------------------------------------------------------------- what others do

    @Test
    void aMessageArrives() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        w.svc.receive(CHAT, "nuevo");
        FxWait.until("it's listed", w::showsNewest);
        frames.stop("a message arriving while reading older ones");
        assertTrue(FxWait.fx(w::jumpDownShown), "the way down is offered");
    }

    @Test
    void manyMessagesArrive() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        for (int i = 0; i < 15; i++) w.svc.receive(CHAT, "ráfaga " + i + "\ncon otra línea");
        FxWait.until("all listed", () -> w.newest().body().startsWith("ráfaga 14") && w.showsNewest());
        frames.stop("fifteen messages arriving while reading older ones");
    }

    @Test
    void someoneTypes() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        w.svc.othersTyping(CHAT, true);
        FxWait.settled();
        w.svc.othersTyping(CHAT, false);
        frames.stop("someone typing while reading older ones");
    }

    @Test
    void theNewestMessageChangesBelow() throws Exception {
        w = TimelineWindow.open(30);
        w.svc.sendText(CHAT, "uno más", null).get();
        w.untilAtBottom();
        TimelineWindow.Frames frames = w.readingOlder();
        w.svc.toggleReaction(CHAT, w.newest().id(), "❤️").get();
        w.svc.editMessage(CHAT, w.newest().id(), "uno más\neditado").get();
        FxWait.settled();
        w.svc.rewrite(CHAT, w.newest().id(), "uno más\n" + "y crece\n".repeat(10));
        frames.stop("the newest message changing below while reading older ones");
    }

    @Test
    void aMessageAboveTheOneReadGetsLongerThenShorter() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        Message above = around()[0];
        w.svc.editMessage(CHAT, above.id(), "ahora\nmucho\nmás\nlargo\nque\nantes").get();
        FxWait.settled();
        w.svc.editMessage(CHAT, above.id(), "corto").get();
        frames.stop("the message above edited longer, then shorter");
    }

    @Test
    void aMessageAboveTheOneReadGetsAReaction() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        Message above = around()[0];
        w.svc.toggleReaction(CHAT, above.id(), "👍").get();
        FxWait.settled();
        w.svc.toggleReaction(CHAT, above.id(), "👍").get();
        frames.stop("a reaction on the message above, added and taken back");
    }

    @Test
    void aMessageAboveTheOneReadIsDeleted() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        w.svc.deleteMessage(CHAT, around()[0].id()).get();
        frames.stop("the message above deleted");
    }

    @Test
    void aMessageBelowTheOneReadChanges() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        Message below = around()[1];
        w.svc.editMessage(CHAT, below.id(), "debajo\ny\nmás\nlargo").get();
        w.svc.toggleReaction(CHAT, below.id(), "🔥").get();
        frames.stop("the message below edited and reacted to");
    }

    @Test
    void olderMessagesLoadAbove() throws Exception {
        w = TimelineWindow.open(30);
        ScriptedChatService.Gate history = w.svc.olderToLoad(CHAT, 20);
        // Up to the very top, where the history is asked for (the first message in view isn't enough: on Linux it
        // showed with the list not quite at its top). It arrives only when let through, with every frame watched.
        for (int i = 0; i < 100 && !w.svc.historyAsked(CHAT); i++) w.wheel(240);
        assertTrue(w.svc.historyAsked(CHAT), "precondition: at the top, the history is asked for");
        FxWait.settled();
        String read = FxWait.fx(w::readMessage);
        TimelineWindow.Frames frames = w.watch(w.stays(read, FxWait.fx(() -> w.yOf(read))));
        history.release();
        FxWait.until("the older messages arrived", () -> w.svc.messages(CHAT).getFirst().body().startsWith("Antiguo"));
        FxWait.until("and are listed", () -> w.list().getItems().size() > 40);
        frames.stop("twenty older messages loaded above the one read");
    }

    // ---------------------------------------------------------------- the window around it

    @Test
    void writingInTheBox() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        w.type("pensando\nqué\nresponder");
        FxWait.fx(() -> { w.input().clear(); return null; });
        frames.stop("the box growing and shrinking while reading older ones");
        assertTrue(FxWait.fx(w::jumpDownShown), "still reading older ones");
    }

    @Test
    void answeringTheMessageReadOpensItsBanner() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        String read = FxWait.fx(w::readMessage);
        Message m = FxWait.fx(() -> w.svc.messages(CHAT).stream().filter(x -> x.id().equals(read)).findFirst().orElseThrow());
        FxWait.fx(() -> { w.composer().reply(m); return null; });
        FxWait.settled();
        w.key(KeyCode.ESCAPE, false);
        frames.stop("the reply banner opening and closing while reading older ones");
    }

    @Test
    void theWindowGetsShorterAndTaller() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        for (double h : new double[] {480, 620, 560}) {
            FxWait.fx(() -> { w.stage.setHeight(h); return null; });
            FxWait.settled();
        }
        frames.stop("the window resized while reading older ones");
    }

    @Test
    void theSidePanelOpensAndCloses() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        FxWait.fx(() -> { w.view.toggleInfo(); return null; });
        FxWait.settled();
        FxWait.fx(() -> { w.view.toggleInfo(); return null; });
        frames.stop("the side panel opening and closing while reading older ones");
    }

    @Test
    void theTextGetsBigger() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        try {
            w.zoom(KeyCode.EQUALS);
        } finally {
            w.zoom(KeyCode.DIGIT0);
        }
        frames.stop("the text size changed while reading older ones");
    }

    @Test
    void aSmallTurnUpStaysUp() throws Exception {
        w = TimelineWindow.open(60);
        w.wheel(30);
        FxWait.settled();
        String read = FxWait.fx(w::readMessage);
        TimelineWindow.Frames frames = w.watch(w.stays(read, FxWait.fx(() -> w.yOf(read))));
        FxWait.pulses(10); // the frames in which it used to be pulled back
        frames.stop("a small turn of the wheel up");
        assertTrue(FxWait.fx(w::jumpDownShown), "and the way back down is offered");
    }

    @Test
    void tinyTrackpadMovesUpThenBackDown() throws Exception {
        w = TimelineWindow.open(30);
        for (int i = 0; i < 6; i++) w.wheel(3);
        assertTrue(FxWait.fx(() -> w.newestGap() == null || w.newestGap() > 1), "precondition: moved up a little");
        for (int i = 0; i < 6; i++) w.wheel(-3);
        double gap = w.untilAtBottom();
        assertFalse(FxWait.fx(w::jumpDownShown), "down at the newest: following it");
        followsFromHere(gap);
    }

    // ---------------------------------------------------------------- back to the newest message

    /**
     * The person's rule: only being at the end of the chat follows it. Writing and sending while reading older messages
     * leaves the list where it is; what was sent counts among the new ones below.
     */
    @Test
    void writingAndSendingWhileReadingOlderOnesStaysThere() throws Exception {
        TimelineWindow.Frames frames = readingOlder();
        w.type("lo que acabo\nde escribir");
        w.key(KeyCode.ENTER, false);
        FxWait.until("it's sent and listed", () -> w.newest().body().startsWith("lo que") && w.showsNewest());
        frames.stop("writing and sending while reading older ones");
        assertTrue(FxWait.fx(w::jumpDownShown), "still reading older ones");
        assertEquals("1 nuevo", FxWait.fx(() -> w.jumpDown().getText()), "what was sent is new below");
    }

    @Test
    void theIndicatorSaysWhetherTheListFollows() throws Exception {
        w = TimelineWindow.open(30);
        assertTrue(FxWait.fx(w::followingShown), "at the newest: up to date");
        assertEquals("Al día", FxWait.fx(() -> ((javafx.scene.control.Label) w.scene.getRoot().lookup(".following-badge")).getText()));
        w.scrollUp();
        assertFalse(FxWait.fx(w::followingShown), "reading older ones");
        assertNull(FxWait.fx(() -> w.jumpDown().getText()), "nothing new yet");
        w.svc.receive(CHAT, "uno");
        w.svc.receive(CHAT, "dos");
        w.svc.sendText(CHAT, "y el mío", null).get();
        FxWait.until("three new below", () -> "3 nuevos".equals(w.jumpDown().getText()));
        assertTrue(FxWait.fx(() -> w.jumpDown().getAccessibleText()).contains("3"), "a screen reader hears how many");
        FxWait.fx(() -> { w.jumpDown().fire(); return null; });
        w.untilAtBottom();
        assertTrue(FxWait.fx(w::followingShown), "down at the newest: up to date again");
    }

    @Test
    void theJumpDownButton() throws Exception {
        w = TimelineWindow.open(30);
        w.scrollUp();
        FxWait.fx(() -> { w.scene.getRoot().lookup(".jump-down").fireEvent(new javafx.event.ActionEvent()); return null; });
        double gap = w.untilAtBottom();
        assertFalse(FxWait.fx(w::jumpDownShown));
        followsFromHere(gap);
    }

    @Test
    void theJumpDownButtonWhileMessagesKeepArriving() throws Exception {
        w = TimelineWindow.open(30);
        w.scrollUp();
        FxWait.fx(() -> {
            w.scene.getRoot().lookup(".jump-down").fireEvent(new javafx.event.ActionEvent());
            w.svc.receive(CHAT, "llega en el mismo instante");
            return null;
        });
        w.svc.receive(CHAT, "y otro después");
        FxWait.until("both show", () -> "y otro después".equals(w.newest().body()) && w.showsNewest());
        followsFromHere(w.untilAtBottom());
    }

    @Test
    void wheelingBackDown() throws Exception {
        w = TimelineWindow.open(30);
        w.scrollUp();
        for (int i = 0; i < 40 && FxWait.fx(w::jumpDownShown); i++) w.wheel(-120);
        assertFalse(FxWait.fx(w::jumpDownShown), "down at the newest again");
        followsFromHere(w.untilAtBottom());
    }

    @Test
    void pageUpThenTheEndKey() throws Exception {
        w = TimelineWindow.open(30);
        w.listKey(KeyCode.PAGE_UP);
        w.listKey(KeyCode.PAGE_UP);
        assertTrue(FxWait.fx(w::jumpDownShown), "page up leaves the newest");
        w.listKey(KeyCode.END);
        assertFalse(FxWait.fx(w::jumpDownShown), "the End key goes back to it");
        followsFromHere(w.untilAtBottom());
    }

    @Test
    void draggingTheScrollbarUpAndBackDown() throws Exception {
        w = TimelineWindow.open(30);
        w.dragScrollbar(0.3);
        assertTrue(FxWait.fx(w::jumpDownShown), "dragged up: reading older ones");
        String read = FxWait.fx(w::readMessage);
        TimelineWindow.Frames frames = w.watch(w.stays(read, FxWait.fx(() -> w.yOf(read))));
        w.svc.receive(CHAT, "mientras arrastraba");
        FxWait.until("it's listed", w::showsNewest);
        frames.stop("a message arriving after dragging up");
        w.dragScrollbar(1);
        assertFalse(FxWait.fx(w::jumpDownShown), "dragged to the end: following the newest");
        followsFromHere(w.untilAtBottom());
    }

    @Test
    void leavingTheChatAndComingBack() throws Exception {
        w = TimelineWindow.open(30);
        w.scrollUp();
        FxWait.fx(() -> { w.view.openChatId("!familia:demo"); return null; });
        FxWait.settled();
        w.svc.receive(CHAT, "mientras no mirabas");
        w.openChat();
        assertFalse(FxWait.fx(w::jumpDownShown));
        followsFromHere(w.untilAtBottom());
    }

    @Test
    void switchingChatsQuickly() throws Exception {
        w = TimelineWindow.open(30);
        w.scrollUp();
        FxWait.fx(() -> {
            w.view.openChatId("!familia:demo");
            w.view.openChatId(CHAT);
            w.view.openChatId("!familia:demo");
            return null;
        });
        w.openChat();
        assertFalse(FxWait.fx(w::jumpDownShown), "each chat opens at its newest");
        followsFromHere(w.untilAtBottom());
    }

    /** At the newest message again, the next one to arrive is followed without a frame out of place. */
    private void followsFromHere(double gap) throws Exception {
        TimelineWindow.Frames frames = w.watch(w.atBottom(gap));
        w.svc.receive(CHAT, "y otro más");
        FxWait.until("it shows", () -> "y otro más".equals(w.newest().body()) && w.showsNewest());
        frames.stop("the next message, once back at the newest");
    }
}

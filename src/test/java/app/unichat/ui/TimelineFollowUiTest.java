package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.settings.Settings;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * At the newest message, frame by frame: nothing that happens (writing, sending, the box growing and shrinking,
 * statuses, someone typing or writing, a bubble growing, the window resized, the text size) ever draws a frame with the
 * newest message cut off or the list moved. A frame drawn wrong and fixed in the next is what a person sees as the
 * list jumping, so every frame is checked. Reading older messages: {@link TimelineReadingUiTest}.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class TimelineFollowUiTest {
    private TimelineWindow w;

    @AfterEach
    void close() throws Exception {
        if (w != null) w.close();
    }

    /** The chat open at its newest message, and the frames watched from there: the newest stays where it is. */
    private TimelineWindow.Frames atBottom(int messages) throws Exception {
        w = TimelineWindow.open(messages);
        double gap = w.untilAtBottom();
        return w.watch(w.atBottom(gap));
    }

    // ---------------------------------------------------------------- at the newest message: writing and sending

    @Test
    void sendingALineWithEnter() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.type("hola");
        w.key(KeyCode.ENTER, false);
        FxWait.until("it's sent", () -> "hola".equals(w.newest().body()) && w.showsNewest());
        frames.stop("sending a line");
    }

    /** What the person saw: the message went down, then the list jumped up a little, as the box shrank back to one line. */
    @Test
    void sendingSeveralLinesAsTheBoxShrinksBack() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.type("hola\nqué tal\ncómo vas\ntodo bien");
        w.key(KeyCode.ENTER, false);
        FxWait.until("it's sent", () -> w.newest().body().startsWith("hola") && w.showsNewest());
        assertEquals("", FxWait.fx(() -> w.input().getText()), "the box is empty again");
        frames.stop("sending four lines");
    }

    @Test
    void writingLinesThatGrowTheBox() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        for (int i = 0; i < 6; i++) w.key(KeyCode.ENTER, true); // Shift+Enter: a new line
        w.type("uno\ndos\ntres\ncuatro\ncinco\nseis\nsiete\nocho");
        frames.stop("the box growing line by line");
    }

    @Test
    void erasingWhatWasWrittenShrinksTheBox() throws Exception {
        w = TimelineWindow.open(20);
        w.type("uno\ndos\ntres\ncuatro\ncinco");
        double gap = w.untilAtBottom();
        TimelineWindow.Frames frames = w.watch(w.atBottom(gap));
        FxWait.fx(() -> { w.input().clear(); return null; });
        frames.stop("the box shrinking without sending");
    }

    @Test
    void sendingSeveralMessagesOneAfterAnother() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        for (int i = 0; i < 5; i++) {
            w.type("mensaje " + i + (i % 2 == 0 ? "\ncon otra línea" : ""));
            w.key(KeyCode.ENTER, false);
        }
        FxWait.until("all five are sent", () -> w.newest().body().startsWith("mensaje 4") && w.showsNewest());
        frames.stop("five messages in a row");
    }

    @Test
    void aMessageTallerThanTheWindowShowsItsEnd() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.svc.sendText(CHAT, "largo\n".repeat(60) + "fin", null).get();
        FxWait.until("it shows", w::showsNewest);
        frames.stop("a message taller than the window");
    }

    @Test
    void sendingASticker() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.svc.sendSticker(CHAT, new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://demo/sticker-scroll", "image/png", 256, 256, null), null).get();
        FxWait.until("it shows", w::showsNewest);
        frames.stop("a sticker");
    }

    // ---------------------------------------------------------------- at the newest message: what others do

    @Test
    void theStatusOfWhatWasSentChanges() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.svc.sendText(CHAT, "¿llegó?", null).get();
        FxWait.until("read", () -> w.newest().status() == MessageStatus.READ && w.showsNewest());
        frames.stop("sending → sent → delivered → read");
    }

    @Test
    void aMessageArrives() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.svc.receive(CHAT, "te escribo\ncon dos líneas");
        FxWait.until("it shows", w::showsNewest);
        frames.stop("a message arriving");
    }

    @Test
    void manyMessagesArriveAtOnce() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        for (int i = 0; i < 10; i++) w.svc.receive(CHAT, "ráfaga " + i);
        FxWait.until("all show", () -> "ráfaga 9".equals(w.newest().body()) && w.showsNewest());
        frames.stop("ten messages at once");
    }

    @Test
    void someoneStartsAndStopsTyping() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.svc.othersTyping(CHAT, true);
        FxWait.settled();
        w.svc.othersTyping(CHAT, false);
        w.svc.receive(CHAT, "listo");
        FxWait.until("the answer shows", w::showsNewest);
        frames.stop("someone typing, then writing");
    }

    @Test
    void aReactionOnTheNewestMessage() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.svc.toggleReaction(CHAT, w.newest().id(), "👍").get();
        FxWait.settled();
        w.svc.toggleReaction(CHAT, w.newest().id(), "👍").get();
        frames.stop("a reaction added and taken back");
    }

    @Test
    void theNewestMessageIsDeleted() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.svc.deleteMessage(CHAT, w.newest().id()).get();
        frames.stop("the newest message deleted");
    }

    @Test
    void theNewestMessageIsEdited() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.svc.editMessage(CHAT, w.newest().id(), "corto").get();
        FxWait.settled();
        w.svc.editMessage(CHAT, w.newest().id(), "ahora\nes\nmucho\nmás\nlargo").get();
        frames.stop("the newest message edited shorter, then longer");
    }

    @Test
    void theNewestMessageGrowsAfterItShows() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        // Like a photo finishing its download: the bubble gets taller after it was drawn.
        FxWait.fx(() -> {
            Region r = (Region) w.flow().getLastVisibleCell().getGraphic();
            r.setMinHeight(r.getHeight() + 220);
            return null;
        });
        frames.stop("the newest bubble growing");
    }

    // ---------------------------------------------------------------- at the newest message: the window around it

    @Test
    void answeringAMessageOpensAndClosesItsBanner() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        Message first = w.svc.messages(CHAT).getFirst();
        FxWait.fx(() -> { w.composer().reply(first); return null; });
        FxWait.settled();
        w.key(KeyCode.ESCAPE, false);
        frames.stop("the reply banner opening and closing");
    }

    @Test
    void answeringAndSending() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        Message first = w.svc.messages(CHAT).getFirst();
        FxWait.fx(() -> { w.composer().reply(first); return null; });
        w.type("te respondo\nen dos líneas");
        w.key(KeyCode.ENTER, false);
        FxWait.until("it's sent", () -> w.newest().body().startsWith("te respondo") && w.showsNewest());
        frames.stop("a reply sent, the banner gone with it");
    }

    @Test
    void editingTheLastMessageWithTheUpKey() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.key(KeyCode.UP, false); // empty box: edits the last message sent
        FxWait.until("the box holds it", () -> !w.input().getText().isEmpty());
        w.type("editado\ncon otra línea");
        w.key(KeyCode.ENTER, false);
        FxWait.until("it's saved", () -> w.newest().body().startsWith("editado"));
        frames.stop("editing from the box");
    }

    @Test
    void theWindowGetsShorterAndTaller() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        for (double h : new double[] {480, 420, 600, 700, 560}) {
            FxWait.fx(() -> { w.stage.setHeight(h); return null; });
            FxWait.settled();
        }
        frames.stop("the window resized");
    }

    @Test
    void theWindowGetsNarrowerAndWider() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        for (double width : new double[] {860, 760, 1000}) {
            FxWait.fx(() -> { w.stage.setWidth(width); return null; });
            FxWait.settled();
        }
        frames.stop("the window narrower and wider (bubbles re-wrap)");
    }

    @Test
    void anAnswerStreamingIn() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.svc.receive(CHAT, "Pensando");
        FxWait.until("it shows", w::showsNewest);
        String id = FxWait.fx(() -> w.newest().id());
        StringBuilder text = new StringBuilder("Pensando");
        for (int i = 0; i < 15; i++) {
            text.append(i % 4 == 3 ? "\n" : " ").append("palabra").append(i);
            w.svc.rewrite(CHAT, id, text.toString());
            FxWait.pulses(1);
        }
        frames.stop("an answer growing piece by piece, like an AI's");
    }

    @Test
    void aMessageArrivesJustAsTheBoxShrinksBack() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.type("una\ndos\ntres");
        w.key(KeyCode.ENTER, false);
        w.svc.receive(CHAT, "justo a la vez");
        w.svc.receive(CHAT, "y otro");
        FxWait.until("all show", () -> "y otro".equals(w.newest().body()) && w.showsNewest());
        frames.stop("sending while messages arrive");
    }

    @Test
    void aShortChatFillsUpPastTheWindow() throws Exception {
        w = TimelineWindow.open(1, "Hola %d");
        w.untilAtBottom();
        TimelineWindow.Frames frames = w.watch(w.inView());
        for (int i = 0; i < 25; i++) {
            w.svc.receive(CHAT, "llega " + i + (i % 3 == 0 ? "\ncon otra línea" : ""));
            FxWait.pulses(1);
        }
        FxWait.until("all show", () -> "llega 24".equals(w.newest().body().lines().findFirst().orElse("")) && w.showsNewest());
        frames.stop("a chat shorter than the window filling past it");
        w.untilAtBottom();
    }

    @Test
    void aMessageAboveTheNewestChanges() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        java.util.List<Message> all = FxWait.fx(() -> w.svc.messages(CHAT));
        Message above = all.get(all.size() - 2);
        w.svc.editMessage(CHAT, above.id(), "editado\ny más largo\nque antes\nmucho más").get();
        FxWait.settled();
        w.svc.toggleReaction(CHAT, above.id(), "😂").get();
        FxWait.settled();
        w.svc.deleteMessage(CHAT, above.id()).get();
        frames.stop("the message above the newest edited, reacted to and deleted");
    }

    @Test
    void theTextGetsBiggerAndSmaller() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        try {
            w.zoom(KeyCode.EQUALS);
            w.zoom(KeyCode.EQUALS);
            w.zoom(KeyCode.MINUS);
        } finally {
            w.zoom(KeyCode.DIGIT0);
        }
        frames.stop("the text size changed");
    }

    @Test
    void theSidePanelOpensAndCloses() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        FxWait.fx(() -> { w.view.toggleInfo(); return null; });
        FxWait.settled();
        FxWait.fx(() -> { w.view.toggleInfo(); return null; });
        frames.stop("the side panel opening and closing (the list narrower, bubbles re-wrapped)");
    }

    @Test
    void sendingAFileShowsItsBarAboveTheBox() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        java.nio.file.Path file = java.nio.file.Files.createTempFile(Settings.dataDir(), "scroll", ".txt");
        java.nio.file.Files.writeString(file, "hola");
        FxWait.fx(() -> { w.composer().sendFiles(java.util.List.of(file.toFile())); return null; });
        FxWait.until("it's sent", () -> file.getFileName().toString().equals(w.newest().fileName()) && w.showsNewest());
        frames.stop("a file sent, its bar coming and going");
    }

    @Test
    void scrollingDownPastTheEndStaysThere() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        for (int i = 0; i < 5; i++) w.wheel(-120);
        w.svc.receive(CHAT, "sigue abajo");
        FxWait.until("it shows", w::showsNewest);
        frames.stop("the wheel down at the end, then a message");
    }

    @Test
    void theEndAndPageDownKeysAtTheNewest() throws Exception {
        TimelineWindow.Frames frames = atBottom(20);
        w.listKey(KeyCode.END);
        w.listKey(KeyCode.PAGE_DOWN);
        w.listKey(KeyCode.DOWN);
        w.svc.receive(CHAT, "y llega otro");
        FxWait.until("it shows", w::showsNewest);
        frames.stop("keys that go down, already at the newest");
    }

    @Test
    void aVeryShortWindow() throws Exception {
        w = TimelineWindow.open(20);
        FxWait.fx(() -> { w.stage.setHeight(330); return null; });
        double gap = w.untilAtBottom();
        TimelineWindow.Frames frames = w.watch(w.atBottom(gap));
        w.type("en\nuna\nventana\nbajita");
        w.key(KeyCode.ENTER, false);
        w.svc.receive(CHAT, "respuesta");
        FxWait.until("it shows", () -> "respuesta".equals(w.newest().body()) && w.showsNewest());
        frames.stop("writing and sending in a short window");
    }
}

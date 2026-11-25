package app.unichat.ui;

import app.unichat.service.ScriptedChatService;
import app.unichat.settings.Settings;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Cargar mensajes anteriores" above the message list ({@code timeline/OlderBar}): there only while the chat has older
 * history, a working state while it loads, gone at the chat's start, an error said and the button back, by keyboard too,
 * and what's read never moves (every frame watched).
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class OlderMessagesButtonUiTest {
    private static final String LOAD = "Cargar mensajes anteriores";
    private static final String LOADING = "Cargando mensajes anteriores…";
    private static final String LONG = "Mensaje largo %d\nsegunda línea\ntercera línea\ncuarta línea";

    @SuppressWarnings("NullAway.Init") // each test sets it before use
    private TimelineWindow w;

    @AfterEach
    void close() throws Exception {
        if (w != null) w.close();
        Settings.get().setFontScale(1.0);
        Settings.get().setHighContrast(Settings.Mode.SYSTEM);
    }

    private Button button() { return (Button) w.scene.getRoot().lookup(".older-load"); }

    private Node bar() { return w.scene.getRoot().lookup(".older-bar"); }

    private boolean shown() { return bar().isVisible() && bar().isManaged(); }

    private boolean wheelShown() {
        Node g = button().getGraphic();
        return g != null && g.isVisible();
    }

    private int count() throws InterruptedException { return FxWait.fx(() -> w.svc.messages(CHAT).size()); }

    private void press() throws InterruptedException {
        FxWait.onFx(() -> button().fire());
    }

    @Test
    void itShowsWhenThereIsMoreHistoryAndNotOtherwise() throws Exception {
        w = TimelineWindow.open(30, "mensaje %d", svc -> svc.olderToLoad(CHAT, 20));
        assertTrue(FxWait.fx(this::shown), "older history to load: the button is there");
        assertEquals(LOAD, FxWait.fx(() -> button().getText()));
        w.close();

        w = TimelineWindow.open(30, "mensaje %d");
        assertFalse(FxWait.fx(this::shown), "a chat whose start is loaded (nothing held back or to load): no button");
    }

    /** A short chat with nothing before it: no request, and not a frame of "Cargando…" on opening it. */
    @Test
    void aShortChatWithNothingBeforeItNeverFlashesTheBar() throws Exception {
        w = TimelineWindow.open(3, "mensaje %d"); // fewer than 20: opening it used to ask for its history at once
        boolean[] everShown = {false};
        FxWait.onFx(() -> bar().visibleProperty().addListener((o, a, v) -> everShown[0] |= v));
        int before = w.svc.historyRequests(CHAT);
        FxWait.onFx(() -> w.view.openChatId("!family:demo"));
        w.openChat();
        FxWait.settled();
        assertEquals(before, w.svc.historyRequests(CHAT), "nothing to ask: nothing asked");
        assertFalse(everShown[0], "the bar never showed, not even for a moment");
    }

    /**
     * A short chat with nothing before it: its scrollbar reaching the top asks for nothing. It did, now and then, in
     * full runs (a layout moved the bar to its top), and the history was asked for a chat that has none.
     */
    @Test
    void reachingTheTopOfAChatWithNothingBeforeItAsksNothing() throws Exception {
        w = TimelineWindow.open(3, "mensaje %d");
        FxWait.settled();
        int before = w.svc.historyRequests(CHAT);
        FxWait.onFx(() -> {
            for (Node n : w.flow().lookupAll(".scroll-bar")) {
                if (n instanceof javafx.scene.control.ScrollBar sb && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                    sb.setValue(sb.getMax());
                    sb.setValue(sb.getMin());
                }
            }
        });
        FxWait.settled();
        assertEquals(before, w.svc.historyRequests(CHAT), "nothing before it: nothing asked");
    }

    /** A failed load is said once; nothing asks again until the person presses the button. */
    @Test
    void aFailedLoadIsNotTriedAgainUnasked() throws Exception {
        w = TimelineWindow.open(40, LONG, svc -> svc.holdBack(CHAT, 10));
        w.svc.failNextHistory(CHAT);
        int before = w.svc.historyRequests(CHAT);
        press();
        FxWait.until("the failure is said", () -> w.scene.getRoot().lookup(".toast") instanceof Label t && t.isVisible()
                && t.getText().startsWith("No se pudieron cargar los mensajes anteriores"));
        FxWait.settled();
        assertEquals(before + 1, w.svc.historyRequests(CHAT), "one request, and no other on its own");
        assertTrue(FxWait.fx(() -> shown() && LOAD.equals(button().getText())), "the button waits for the person");
    }

    /** What waited for a failed load (going to a pinned message) gives up with it: a later load doesn't jump there. */
    @Test
    void aPinnedMessageWaitingForAFailedLoadDoesntJumpLater() throws Exception {
        app.unichat.model.Message[] held = new app.unichat.model.Message[1];
        w = TimelineWindow.open(40, LONG, svc -> {
            held[0] = svc.messages(CHAT).getFirst(); // the oldest, in the page held back
            svc.holdBack(CHAT, 10);
        });
        app.unichat.model.Message pinned = held[0];
        String pinnedId = pinned.id();
        try {
            FxWait.onFx(() -> app.unichat.service.PinnedMessages.get().pin(CHAT, pinned));
            w.svc.failNextHistory(CHAT);
            int asked = w.svc.historyRequests(CHAT);
            FxWait.onFx(() -> ((Button) w.scene.getRoot().lookup(".pinned-go")).fire());
            FxWait.until("the load failed: the button is back", () -> shown() && LOAD.equals(button().getText())
                    && w.svc.historyRequests(CHAT) == asked + 1);
            FxWait.settled();
            int before = count();
            press(); // the person's own load
            FxWait.until("the page came", () -> w.svc.messages(CHAT).size() > before);
            FxWait.settled();
            assertTrue(FxWait.fx(() -> w.svc.messages(CHAT).stream().anyMatch(m -> m.id().equals(pinnedId))),
                    "precondition: the pinned one is loaded now");
            assertFalse(FxWait.fx(() -> w.list().getSelectionModel().getSelectedItem() instanceof app.unichat.ui.timeline.ThreadRows.MessageRow mr
                    && mr.message().id().equals(pinnedId)), "the failed reveal didn't come back to jump to the pinned one");
            assertEquals(asked + 2, w.svc.historyRequests(CHAT), "and no load went on looking for it");
        } finally {
            FxWait.onFx(() -> app.unichat.service.PinnedMessages.get().unpin(CHAT, pinnedId));
        }
    }

    /** One chat's load answering after another chat was opened leaves that chat's own load working. */
    @Test
    void aLateAnswerForAnotherChatLeavesThisChatsLoadWorking() throws Exception {
        String other = "!family:demo";
        ScriptedChatService.Gate[] first = new ScriptedChatService.Gate[1];
        w = TimelineWindow.open(30, LONG, svc -> first[0] = svc.olderToLoad(CHAT, 5));
        press(); // CHAT's load, held
        FxWait.until("loading", () -> LOADING.equals(button().getText()));
        ScriptedChatService.Gate second = w.svc.olderToLoad(other, 5);
        FxWait.onFx(() -> w.view.openChatId(other));
        FxWait.settled();
        if (!LOADING.equals(FxWait.fx(() -> button().getText()))) press(); // its own load, held (if opening didn't start it)
        FxWait.until("the other chat loads", () -> LOADING.equals(button().getText()));
        first[0].release(); // CHAT's answer, late
        FxWait.settled();
        assertEquals(LOADING, FxWait.fx(() -> button().getText()), "this chat's load still going: still working");
        assertTrue(FxWait.fx(this::wheelShown));
        int asked = w.svc.historyRequests(other);
        press(); // still loading: the press asks nothing more
        FxWait.settled();
        assertEquals(asked, w.svc.historyRequests(other), "the late answer didn't end this chat's load: no second request");
        second.release();
        FxWait.until("this chat's own answer ends it", () -> !LOADING.equals(button().getText()));
    }

    @Test
    void aClickLoadsTheOlderMessagesWithAWorkingStateAndWhatsReadNeverMoves() throws Exception {
        ScriptedChatService.Gate[] gate = new ScriptedChatService.Gate[1];
        w = TimelineWindow.open(30, LONG, svc -> gate[0] = svc.olderToLoad(CHAT, 20));
        ScriptedChatService.Gate history = gate[0];
        assertTrue(FxWait.fx(this::shown), "precondition: the button is there");
        TimelineWindow.Frames frames = w.readingOlder();
        assertFalse(w.svc.historyAsked(CHAT), "precondition: nothing asked before the click");
        int before = count();

        press();
        assertTrue(w.svc.historyAsked(CHAT), "the click asks for the history");
        FxWait.settled();
        assertEquals(LOADING, FxWait.fx(() -> button().getText()), "it says it's working");
        assertEquals(LOADING, FxWait.fx(() -> button().getAccessibleText()), "a screen reader hears it too");
        assertTrue(FxWait.fx(this::wheelShown), "with a wheel");
        assertTrue(FxWait.fx(this::shown), "and stays while it loads");
        press(); // pressed again while it loads: nothing more is asked
        FxWait.settled();

        history.release();
        FxWait.until("the older messages arrived", () -> w.svc.messages(CHAT).getFirst().body().startsWith("Antiguo"));
        FxWait.until("and are listed", () -> w.list().getItems().size() > 50);
        frames.stop("older messages loaded from the button above the one read");
        assertEquals(before + 20, count(), "one page loaded, not two");
        assertFalse(FxWait.fx(this::wheelShown), "the wheel is gone");
    }

    @Test
    void itHidesAtTheChatsStartAndWhatsReadStaysThere() throws Exception {
        // Two pages to load, 10 then the last 5, held back before the chat opens.
        w = TimelineWindow.open(40, LONG, svc -> svc.holdBack(CHAT, 15));
        int all = count() + 15;
        assertTrue(FxWait.fx(this::shown), "the button shows the held history");
        TimelineWindow.Frames frames = w.readingOlder();
        assertEquals(all - 15, count(), "precondition: reading older ones, nothing loaded yet");

        press();
        FxWait.until("the first page came", () -> w.svc.messages(CHAT).size() == all - 5);
        FxWait.settled();
        assertTrue(FxWait.fx(this::shown), "more still to come: the button stays");
        assertEquals(LOAD, FxWait.fx(() -> button().getText()));

        press();
        FxWait.until("the chat's start came", () -> w.svc.messages(CHAT).size() == all);
        FxWait.until("the button is gone", () -> !shown());
        FxWait.settled();
        frames.stop("the bar going away at the chat's start");
    }

    @Test
    void anErrorIsSaidAndTheButtonComesBack() throws Exception {
        w = TimelineWindow.open(30, "mensaje %d", svc -> svc.olderToLoad(CHAT, 20));
        ScriptedChatService.Gate failing = w.svc.olderToLoad(CHAT, 20);
        int before = count();
        press();
        FxWait.until("loading", () -> LOADING.equals(button().getText()));
        failing.fail();
        FxWait.until("the error is said", () -> w.scene.getRoot().lookup(".toast") instanceof Label t && t.isVisible()
                && t.getText().startsWith("No se pudieron cargar los mensajes anteriores"));
        FxWait.until("the button is back to try again", () -> shown() && LOAD.equals(button().getText()) && !wheelShown());
        assertEquals(before, count(), "nothing loaded");

        ScriptedChatService.Gate again = w.svc.olderToLoad(CHAT, 20);
        press();
        again.release();
        FxWait.until("tried again, it loads", () -> w.svc.messages(CHAT).size() == before + 20);
    }

    @Test
    void theKeyboardReachesAndPressesItWithItsAccessibleText() throws Exception {
        w = TimelineWindow.open(40, LONG, svc -> svc.holdBack(CHAT, 10)); // one page: then the chat's start
        int all = count() + 10;
        assertTrue(FxWait.fx(this::shown), "the button shows");
        assertTrue(FxWait.fx(() -> button().isFocusTraversable()), "Tab reaches it");
        assertEquals(LOAD, FxWait.fx(() -> button().getAccessibleText()), "what a screen reader says");

        FxWait.onFx(() -> button().requestFocus());
        FxWait.until("it has the focus", () -> w.scene.getFocusOwner() == button());
        FxWait.onFx(() -> {
            button().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false));
            button().fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, " ", " ", KeyCode.SPACE, false, false, false, false));
        });
        FxWait.until("Space loaded the history", () -> w.svc.messages(CHAT).size() == all);
        FxWait.until("the button is gone at the chat's start", () -> !shown());
        FxWait.until("the focus went on to the messages, not nowhere", () -> w.scene.getFocusOwner() == w.list());
        assertTrue(FxWait.fx(() -> w.list().getSelectionModel().getSelectedItem() instanceof app.unichat.ui.timeline.ThreadRows.MessageRow mr
                && mr.message().id().equals(w.svc.messages(CHAT).getFirst().id())), "the oldest message is the one selected");
    }

    @Test
    void nothingIsCutAtTheBiggestTextInHighContrast() throws Exception {
        Settings.get().setFontScale(1.6);
        Settings.get().setHighContrast(Settings.Mode.ON);
        w = TimelineWindow.open(30, "mensaje %d", svc -> svc.olderToLoad(CHAT, 20));
        FxWait.settled();
        assertTrue(FxWait.fx(() -> w.scene.getRoot().getStyleClass().contains("contrast")), "precondition: high contrast");
        assertTrue(FxWait.fx(this::shown));
        javafx.scene.text.Text text = FxWait.fx(() -> (javafx.scene.text.Text) button().lookup(".text"));
        assertEquals(LOAD, FxWait.fx(text::getText), "the whole text, not cut with …");
        assertTrue(FxWait.fx(() -> button().getWidth() >= button().prefWidth(-1) - 0.5), "the button as wide as its text");
        assertTrue(FxWait.fx(() -> button().getHeight() >= button().prefHeight(-1) - 0.5), "and as tall");
        assertTrue(FxWait.fx(() -> button().getFont().getSize() > 16), "the text grew with the setting");
    }
}

package app.unichat.ui.timeline;

import app.unichat.model.Message;
import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.desktop.Notifier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Corregir con IA" in a sent message's menu: it's corrected and edited by itself while the person keeps writing and
 * sending, and only that message shows it's being corrected.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class MessageFixUiTest {
    private static final String CHAT = "!family:demo";
    private MockChatService svc;
    /** For each text sent, whether it was already waiting to be corrected the moment it went. */
    private final List<Boolean> expectedWhenSent = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Stage stage;
    private ThreadPane thread;
    private MainView view;

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

    @BeforeEach
    void open() throws Exception {
        svc = new MockChatService() {
            @Override
            public java.util.concurrent.CompletableFuture<Void> sendText(String chatId, String text, String replyToId) {
                // A server that answers at once confirms it before the next line of the box runs.
                if (thread != null) expectedWhenSent.add(thread.fixer().expecting(chatId, Message.withoutEmoteCommand(text)));
                return super.sendText(chatId, text, replyToId);
            }
        };
        AiReplyButton.SHOWN.set(true);
        thread = FxWait.fx(() -> {
            stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            stage.setScene(new Scene(view, 900, 700));
            stage.show();
            view.openChatId(CHAT);
            return find(view);
        });
    }

    @AfterEach
    void close() throws Exception {
        AiReplyButton.SHOWN.set(false);
        svc.stop();
        FxWait.fx(() -> { // the view goes with its window: disposed and out of its scene (src/test/CLAUDE.md)
            view.dispose();
            stage.close();
            stage.getScene().setRoot(new javafx.scene.layout.Pane());
            return null;
        });
    }

    private static ThreadPane find(Node n) {
        if (n instanceof ThreadPane t) return t;
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) {
            ThreadPane t = find(c);
            if (t != null) return t;
        }
        return null;
    }

    private List<String> menu(Message m) throws InterruptedException {
        return FxWait.fx(() -> thread.actions().messageMenu(m).getItems().stream().map(MenuItem::getText).toList());
    }

    private long fixingNotes() throws InterruptedException {
        return FxWait.fx(() -> thread.lookupAll(".message-fixing").stream().filter(Node::isVisible).count());
    }

    @Test
    void aSentMessageIsCorrectedWhileThePersonKeepsSending() throws Exception {
        CountDownLatch answer = new CountDownLatch(1);
        thread.fixer().useWriter(() -> prompt -> {
            try {
                answer.await(); // the AI takes its time
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "creo que mañana no puedo";
        });
        svc.sendText(CHAT, "creo q mañana no puedo", null).get();
        Message m = svc.messages(CHAT).getLast();
        m.setStatus(app.unichat.model.MessageStatus.SENT); // the server has it: only then can it be edited
        FxWait.until("listed", () -> FxWait.fx(() -> !thread.lookupAll(".bubble").isEmpty()));
        MenuItem fix = FxWait.fx(() -> thread.actions().messageMenu(m).getItems().stream()
                .filter(i -> "Corregir con IA".equals(i.getText())).findFirst().orElseThrow());
        FxWait.fx(() -> { fix.fire(); return null; });
        FxWait.until("only that message says it's being corrected", () -> fixingNotes() == 1);
        assertFalse(menu(m).contains("Corregir con IA"), "not asked twice while going on");

        // Nothing waits for it: writing and sending go on.
        TextArea box = FxWait.fx(() -> (TextArea) thread.lookup(".composer .text-area"));
        FxWait.fx(() -> {
            box.setText("otro mensaje");
            box.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        FxWait.until("the next one went", () -> svc.messages(CHAT).getLast().body().equals("otro mensaje"));
        assertEquals("creo q mañana no puedo", m.body(), "still being corrected");

        answer.countDown();
        FxWait.until("corrected and edited", () -> m.body().equals("creo que mañana no puedo") && m.edited());
        FxWait.until("no longer said", () -> fixingNotes() == 0);
    }

    @Test
    void withCorrectingEverythingOnWhatsSentGoesAtOnceAndIsCorrectedAfter() throws Exception {
        thread.fixer().useWriter(() -> prompt -> prompt.substring(prompt.indexOf(">\n") + 2, prompt.indexOf("\n</")).replace(" q ", " que "));
        app.unichat.assist.AiSettings.setFixMode(app.unichat.assist.AiSettings.FixMode.AFTER);
        try {
            TextArea box = FxWait.fx(() -> (TextArea) thread.lookup(".composer .text-area"));
            FxWait.fx(() -> {
                box.setText("creo q sí");
                box.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                return null;
            });
            Message m = svc.messages(CHAT).getLast();
            assertEquals("creo q sí", m.body(), "it went at once, as written");
            assertEquals(List.of(true), expectedWhenSent, "told before it went, not after");
            FxWait.until("corrected once the server had it", () -> m.body().equals("creo que sí") && m.edited());
        } finally {
            app.unichat.assist.AiSettings.setFixMode(app.unichat.assist.AiSettings.FixMode.OFF);
        }
    }

    @Test
    void withCorrectingEverythingOnAnEmoteIsCorrectedToo() throws Exception {
        thread.fixer().useWriter(() -> prompt -> prompt.substring(prompt.indexOf(">\n") + 2, prompt.indexOf("\n</")).replace(" q ", " que "));
        app.unichat.assist.AiSettings.setFixMode(app.unichat.assist.AiSettings.FixMode.AFTER);
        try {
            TextArea box = FxWait.fx(() -> (TextArea) thread.lookup(".composer .text-area"));
            FxWait.fx(() -> {
                box.setText("/me dice q sí");
                box.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                return null;
            });
            Message m = svc.messages(CHAT).getLast();
            assertEquals("dice q sí", m.body(), "sent without the command");
            FxWait.until("corrected", () -> m.body().equals("dice que sí"));
        } finally {
            app.unichat.assist.AiSettings.setFixMode(app.unichat.assist.AiSettings.FixMode.OFF);
        }
    }

    @Test
    void withCorrectingEverythingOffWhatsSentStaysAsWritten() throws Exception {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        thread.fixer().useWriter(() -> prompt -> {
            asked.incrementAndGet();
            return "otra cosa";
        });
        TextArea box = FxWait.fx(() -> (TextArea) thread.lookup(".composer .text-area"));
        FxWait.fx(() -> {
            box.setText("creo q sí");
            box.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        Message m = svc.messages(CHAT).getLast();
        FxWait.until("the server has it", () -> m.status() != app.unichat.model.MessageStatus.SENDING);
        thread.fixer().messagesChanged(CHAT); // the change that would have set it off, done
        // Had it been set off, it's going on from that call on (or over): wait for nothing going on, then look.
        FxWait.until("nothing being corrected", () -> !thread.fixer().fixing(m.id()));
        assertEquals(0, asked.get());
        assertEquals("creo q sí", m.body());
    }

    @Test
    void withoutTheAiTurnedOnItIsntOffered() throws Exception {
        svc.sendText(CHAT, "hola", null).get();
        Message m = svc.messages(CHAT).getLast();
        m.setStatus(app.unichat.model.MessageStatus.SENT);
        assertTrue(menu(m).contains("Corregir con IA"));
        AiReplyButton.SHOWN.set(false);
        assertFalse(menu(m).contains("Corregir con IA"));
    }
}

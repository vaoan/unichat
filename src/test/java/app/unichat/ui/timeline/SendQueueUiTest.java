package app.unichat.ui.timeline;

import app.unichat.assist.AiSettings;
import app.unichat.model.Message;
import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.desktop.Notifier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correcting before sending, on screen: what's sent waits above the box, a row each, while the person keeps writing and
 * sending; each can be cancelled or sent as written; it goes corrected and in order once ready.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SendQueueUiTest {
    private static final String CHAT = "!family:demo";
    private MockChatService svc;
    private Stage stage;
    private MainView view;
    private ThreadPane thread;
    /** Each text's correction waits for its own gate. */
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();

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
        svc = new MockChatService();
        svc.noAutoReplies();
        AiReplyButton.SHOWN.set(true);
        AiSettings.setFixMode(AiSettings.FixMode.BEFORE);
        thread = FxWait.fx(() -> {
            stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            stage.setScene(new Scene(view, 900, 700));
            stage.show();
            view.openChatId(CHAT);
            return find(view);
        });
        thread.sendQueue().useWriter(() -> prompt -> {
            String text = prompt.substring(prompt.indexOf(">\n") + 2, prompt.indexOf("\n</"));
            try {
                gates.computeIfAbsent(text, t -> new CountDownLatch(1)).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return text.replace(" q ", " que ");
        });
    }

    @AfterEach
    void close() throws Exception {
        gates.values().forEach(CountDownLatch::countDown);
        AiSettings.setFixMode(AiSettings.FixMode.OFF);
        AiReplyButton.SHOWN.set(false);
        svc.stop();
        FxWait.fx(() -> {
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

    private void send(String text) throws Exception {
        TextArea box = FxWait.fx(() -> (TextArea) thread.lookup(".composer .text-area"));
        FxWait.fx(() -> {
            box.setText(text);
            box.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
    }

    private void release(String text) {
        gates.computeIfAbsent(text, t -> new CountDownLatch(1)).countDown();
    }

    /** The texts of the rows waiting, as shown once the window is done. */
    private List<String> rows() throws Exception {
        FxWait.settled();
        return rowsNow();
    }

    /** The same, as they are now: for an until's condition, which may only look. */
    private List<String> rowsNow() throws Exception {
        return FxWait.fx(() -> thread.lookupAll(".send-queue-text").stream().filter(Node::isVisible)
                .map(n -> ((Label) n).getText()).toList());
    }

    private List<String> mine(int from) {
        List<String> all = svc.messages(CHAT).stream().filter(Message::outgoing).map(Message::body).toList();
        return all.subList(from, all.size());
    }

    private int sentSoFar() {
        return (int) svc.messages(CHAT).stream().filter(Message::outgoing).count();
    }

    private Button button(String accessible) throws Exception {
        return FxWait.fx(() -> thread.lookupAll(".send-queue-row .button").stream().map(n -> (Button) n)
                .filter(b -> accessible.equals(b.getAccessibleText())).findFirst().orElseThrow());
    }

    @Test
    void manyWaitWhileThePersonKeepsWritingAndGoCorrectedInOrder() throws Exception {
        int before = sentSoFar();
        send("uno q sí");
        send("dos q no"); // the box was free at once
        assertEquals(List.of("Corrigiendo antes de enviar: «uno q sí»", "Corrigiendo antes de enviar: «dos q no»"), rows());
        assertEquals(List.of(), mine(before), "nothing went yet");
        assertTrue(FxWait.fx(() -> ((TextArea) thread.lookup(".composer .text-area")).getText().isEmpty()), "the box is free");
        release("dos q no");
        release("uno q sí");
        FxWait.until("both went", () -> mine(before).size() == 2);
        assertEquals(List.of("uno que sí", "dos que no"), mine(before), "corrected, in the order written");
        FxWait.until("nothing waiting", () -> rowsNow().isEmpty());
        assertFalse(FxWait.fx(() -> thread.lookup(".send-queue").isVisible()), "hidden with none");
    }

    @Test
    void cancellingSendsNothingAndSendingAsWrittenGoesAtOnce() throws Exception {
        int before = sentSoFar();
        send("uno q sí");
        send("dos q no");
        FxWait.fx(() -> { button("Cancelar el envío de «uno q sí»").fire(); return null; });
        assertEquals(List.of("Corrigiendo antes de enviar: «dos q no»"), rows());
        FxWait.fx(() -> { button("Enviar sin corregir «dos q no»").fire(); return null; });
        assertEquals(List.of("dos q no"), mine(before), "as written, at once; the cancelled one never");
        assertEquals(List.of(), rows());
    }

    @Test
    void eachChatShowsItsOwnQueueAndAnotherChatsKeepsGoing() throws Exception {
        send("uno q sí");
        FxWait.fx(() -> { view.openChatId("!mom:demo"); return null; });
        assertEquals(List.of(), rows(), "not this chat's");
        release("uno q sí");
        FxWait.until("it went while another chat was open", () -> svc.messages(CHAT).getLast().body().equals("uno que sí"));
    }

    @Test
    void manyScrollInsteadOfPushingTheChatUp() throws Exception {
        for (int i = 0; i < 8; i++) send("mensaje " + i + " q sí");
        assertEquals(8, rows().size());
        double shown = FxWait.fx(() -> thread.lookup(".send-queue").getLayoutBounds().getHeight());
        double all = FxWait.fx(() -> ((javafx.scene.control.ScrollPane) thread.lookup(".send-queue")).getContent().getLayoutBounds().getHeight());
        assertTrue(shown < all / 2, "a few rows shown, the rest scroll: " + shown + " of " + all);
    }

    @Test
    void whereMyTextsAreKeptWhatIWroteAndItsCorrectionAreBothVersions() throws Exception {
        svc.setAutoSave(CHAT, true, java.util.EnumSet.of(app.unichat.model.SaveKind.TEXTS)).get();
        int before = sentSoFar();
        send("creo q sí");
        release("creo q sí");
        FxWait.until("it went", () -> mine(before).size() == 1);
        Message m = svc.messages(CHAT).getLast();
        m.setStatus(app.unichat.model.MessageStatus.SENT);
        app.unichat.autosave.AutoSave.of(svc).messagesChanged(CHAT); // the server's word on it: kept as it comes
        javafx.scene.control.MenuItem versions = FxWait.fx(() -> thread.actions().messageMenu(m).getItems().stream()
                .filter(i -> "Ver versiones…".equals(i.getText())).findFirst().orElseThrow());
        FxWait.fx(() -> { versions.fire(); return null; });
        FxWait.until("both versions, each said what it is", () -> {
            List<String> texts = FxWait.fx(() -> {
                Node sheet = stage.getScene().getRoot().lookup(".saved-text-sheet");
                return sheet == null ? List.<String>of() : sheet.lookupAll(".label").stream().map(n -> ((Label) n).getText()).toList();
            });
            return texts.contains("creo q sí") && texts.contains("creo que sí")
                    && texts.stream().anyMatch(t -> t.endsWith("escrito, antes de corregir"))
                    && texts.stream().anyMatch(t -> t.endsWith("corregido con IA"));
        });
    }

    @Test
    void aCommandGoesAsTypedNeverThroughTheAi() throws Exception {
        int before = sentSoFar();
        send("!wa sync");
        assertEquals(List.of("!wa sync"), mine(before), "at once, as typed");
        assertEquals(List.of(), rows());
    }

    @Test
    void eachRowSaysWhatItWouldAnswerIfTheChatMovesOn() throws Exception {
        String last = svc.messages(CHAT).stream().filter(m -> !m.outgoing()).toList().getLast().body();
        send("creo q sí");
        String answers = FxWait.fx(() -> ((Label) thread.lookup(".send-queue-answers")).getText());
        assertTrue(answers.startsWith("Si la conversación avanza, responderá a ") && answers.contains(last.substring(0, Math.min(20, last.length()))),
                answers);
    }

    @Test
    void theKeyboardsFocusStaysOnItsRowWhenAnotherGoes() throws Exception {
        send("uno q sí");
        send("dos q no");
        Button cancelSecond = button("Cancelar el envío de «dos q no»");
        FxWait.fx(() -> { cancelSecond.requestFocus(); return null; });
        release("uno q sí");
        FxWait.until("the first went", () -> rowsNow().size() == 1);
        FxWait.settled();
        assertTrue(FxWait.fx(() -> cancelSecond.getScene() != null && stage.getScene().getFocusOwner() == cancelSecond),
                "the same button, still focused");
    }

    @Test
    void aFileSentAfterATextGoesAfterIt() throws Exception {
        int before = (int) svc.messages(CHAT).size();
        send("mira esto q chévere");
        java.io.File photo = java.nio.file.Files.writeString(java.nio.file.Files.createTempFile("foto", ".txt"), "x").toFile();
        FxWait.fx(() -> { thread.composer().sendFiles(List.of(photo)); return null; });
        assertEquals(before, svc.messages(CHAT).size(), "the file waits its turn");
        release("mira esto q chévere");
        FxWait.until("both went", () -> svc.messages(CHAT).size() == before + 2);
        List<Message> last = svc.messages(CHAT).subList(before, before + 2);
        assertEquals("mira esto que chévere", last.get(0).body(), "the text first");
        photo.delete();
    }

    @Test
    void aRebuiltWindowShowsTheSameQueue() throws Exception {
        send("uno q sí");
        Stage other = FxWait.fx(Stage::new);
        MainView second = FxWait.fx(() -> {
            MainView v = new MainView(svc, other, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            other.setOpacity(0);
            other.setScene(new Scene(v, 900, 700));
            other.show();
            v.openChatId(CHAT); // as a language change rebuilds the window, on the same chat
            return v;
        });
        try {
            assertTrue(FxWait.fx(() -> find(second).sendQueue() == thread.sendQueue()), "one per service: still controllable");
            FxWait.until("its row shows there too", () -> FxWait.fx(() -> find(second).lookupAll(".send-queue-text").stream()
                    .anyMatch(n -> ((Label) n).getText().contains("uno q sí"))));
        } finally {
            FxWait.fx(() -> {
                second.dispose();
                other.close();
                other.getScene().setRoot(new javafx.scene.layout.Pane());
                return null;
            });
        }
    }
}

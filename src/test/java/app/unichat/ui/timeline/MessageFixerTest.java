package app.unichat.ui.timeline;

import app.unichat.assist.ReplyWriter;
import app.unichat.model.Message;
import app.unichat.service.MockChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Corregir con IA" on a message already sent: corrected apart from the message box, then edited by itself. */
class MessageFixerTest {
    private static final String CHAT = "!familia:demo";
    private MockChatService svc;
    private final List<String> told = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    /** What the AI answers for a text it's given: by default each "q" becomes "que". */
    private volatile java.util.function.UnaryOperator<String> answer = t -> t.replace(" q ", " que ");
    private volatile ReplyWriter.Failure failure;
    private MessageFixer fixer;

    @BeforeEach
    void start() {
        svc = new MockChatService();
        fixer = new MessageFixer(svc, () -> new AiReplyButton.Writer() {
            @Override public String write(String prompt) throws ReplyWriter.Failure {
                prompts.add(prompt);
                if (failure != null) throw failure;
                String text = prompt.substring(prompt.indexOf(">\n") + 2, prompt.indexOf("\n</"));
                return answer.apply(text);
            }
        }, told::add, Runnable::run);
    }

    @AfterEach
    void stop() {
        svc.stop();
    }

    private Message sent(String text) throws Exception {
        svc.sendText(CHAT, text, null).get();
        List<Message> all = svc.messages(CHAT);
        return all.getLast();
    }

    @Test
    void aSentMessageIsCorrectedAndEditedByItself() throws Exception {
        Message m = sent("creo q mañana no puedo");
        fixer.fix(m).get();
        assertEquals("creo que mañana no puedo", m.body());
        assertTrue(m.edited());
        assertEquals(List.of("Mensaje corregido"), told);
        assertTrue(prompts.getFirst().contains("Correct the spelling, grammar"), "the same correction as ⌘O");
    }

    @Test
    void nothingToCorrectEditsNothing() throws Exception {
        Message m = sent("todo bien");
        fixer.fix(m).get();
        assertFalse(m.edited());
        assertEquals(List.of("El mensaje ya estaba bien"), told);
    }

    @Test
    void aMessageChangedOrDeletedMeanwhileIsLeftAlone() throws Exception {
        Message m = sent("creo q sí");
        answer = t -> {
            m.setBody("lo cambié yo"); // edited meanwhile, from another device
            return t.replace(" q ", " que ");
        };
        fixer.fix(m).get();
        assertEquals("lo cambié yo", m.body());
        assertEquals(List.of("El mensaje cambió mientras se corregía: quedó como estaba"), told);

        told.clear();
        Message gone = sent("creo q no");
        answer = t -> {
            gone.markDeleted();
            return t;
        };
        fixer.fix(gone).get();
        assertEquals(List.of("El mensaje cambió mientras se corregía: quedó como estaba"), told);
    }

    @Test
    void aFailureSaysWhyAndEditsNothing() throws Exception {
        Message m = sent("creo q sí");
        failure = new ReplyWriter.Failure("Claude Code no está instalado");
        fixer.fix(m).get();
        assertFalse(m.edited());
        assertEquals(List.of("No se pudo corregir el mensaje: Claude Code no está instalado"), told);
        assertFalse(fixer.fixing(m.id()), "done, failed or not");
    }

    @Test
    void eachMessageHasItsOwnTurnAndTheSameOneIsntAskedTwice() throws Exception {
        List<Runnable> queued = new CopyOnWriteArrayList<>();
        MessageFixer held = new MessageFixer(svc, () -> prompt -> {
            String text = prompt.substring(prompt.indexOf(">\n") + 2, prompt.indexOf("\n</"));
            return text.replace(" q ", " que ");
        }, told::add, queued::add);
        List<Integer> changes = new CopyOnWriteArrayList<>();
        held.addListener(() -> changes.add(1));
        Message a = sent("creo q sí"), b = sent("dijo q no");
        CompletableFuture<Void> fa = held.fix(a), fb = held.fix(b);
        assertTrue(held.fixing(a.id()) && held.fixing(b.id()), "both going on, each its own");
        CompletableFuture<Void> again = held.fix(a);
        assertEquals(2, queued.size(), "asking again for one going on starts nothing");
        assertTrue(again.isDone());
        queued.forEach(Runnable::run);
        fa.get();
        fb.get();
        assertEquals("creo que sí", a.body());
        assertEquals("dijo que no", b.body());
        assertFalse(held.fixing(a.id()) || held.fixing(b.id()));
        assertTrue(changes.size() >= 4, "told when each starts and ends, for its indicator");
    }

    @Test
    void whatIsSentHereIsCorrectedByItselfOnceTheServerHasItQuietly() throws Exception {
        fixer.expect(CHAT, "creo q sí");
        svc.sendText(CHAT, "creo q sí", null).get(); // the chat changes: the message arrives
        Message m = svc.messages(CHAT).getLast();
        m.setStatus(app.unichat.model.MessageStatus.SENT); // the server has it
        fixer.messagesChanged(CHAT);
        assertEquals("creo que sí", m.body());
        assertEquals(List.of(), told, "no notice for every message corrected by itself");
        fixer.messagesChanged(CHAT);
        assertEquals(1, prompts.size(), "once");
    }

    @Test
    void whatIsSentFromAnotherDeviceOrStillGoingIsntTouched() throws Exception {
        svc.sendText(CHAT, "creo q sí", null).get(); // from the phone: nobody expected it here
        svc.messages(CHAT).getLast().setStatus(app.unichat.model.MessageStatus.SENT);
        fixer.messagesChanged(CHAT);
        assertEquals(List.of(), prompts);

        fixer.expect(CHAT, "dijo q no");
        svc.sendText(CHAT, "dijo q no", null).get();
        Message m = svc.messages(CHAT).getLast();
        m.setStatus(app.unichat.model.MessageStatus.SENDING); // its id is still a temporary one
        fixer.messagesChanged(CHAT);
        assertEquals(List.of(), prompts, "waits for the server");
        m.setStatus(app.unichat.model.MessageStatus.FAILED);
        fixer.messagesChanged(CHAT);
        m.setStatus(app.unichat.model.MessageStatus.SENT);
        fixer.messagesChanged(CHAT);
        assertEquals(List.of(), prompts, "it didn't go: nothing to correct, and it's forgotten");
    }

    @Test
    void aMessageCorrectedByItselfThatFailsSaysSo() throws Exception {
        failure = new ReplyWriter.Failure("sin red");
        fixer.expect(CHAT, "creo q sí");
        svc.sendText(CHAT, "creo q sí", null).get();
        svc.messages(CHAT).getLast().setStatus(app.unichat.model.MessageStatus.SENT);
        fixer.messagesChanged(CHAT);
        assertEquals(List.of("No se pudo corregir el mensaje: sin red"), told);
    }

    @Test
    void onlyTheMessageSentHereIsCorrectedNotAnOlderOneSayingTheSame() throws Exception {
        Message older = sent("ok q tal"); // from the phone, earlier
        older.setStatus(app.unichat.model.MessageStatus.SENT);
        fixer.expect(CHAT, "ok q tal"); // the box, before the message goes
        svc.sendText(CHAT, "ok q tal", null).get();
        Message now = svc.messages(CHAT).getLast();
        fixer.messagesChanged(CHAT); // still going: nothing yet, and the older one isn't it
        assertEquals("ok q tal", older.body());
        now.setStatus(app.unichat.model.MessageStatus.SENT);
        fixer.messagesChanged(CHAT);
        assertEquals("ok que tal", now.body());
        assertEquals("ok q tal", older.body(), "the phone's untouched");
    }

    @Test
    void aMessageSentHereThatFailedLeavesAnOlderOneSayingTheSameAlone() throws Exception {
        Message older = sent("ok q tal");
        older.setStatus(app.unichat.model.MessageStatus.SENT);
        fixer.expect(CHAT, "ok q tal");
        svc.sendText(CHAT, "ok q tal", null).get();
        svc.messages(CHAT).getLast().setStatus(app.unichat.model.MessageStatus.FAILED);
        fixer.messagesChanged(CHAT);
        fixer.messagesChanged(CHAT);
        assertEquals(List.of(), prompts);
        assertEquals("ok q tal", older.body());
    }

    @Test
    void aCorrectionThatAddsALinkIsntSent() throws Exception {
        Message m = sent("creo q sí");
        answer = t -> t.replace(" q ", " que ") + " https://malo.example/x";
        fixer.fix(m).get();
        assertFalse(m.edited());
        assertEquals(List.of("No se pudo corregir el mensaje: la corrección añadía cosas que no estaban (un enlace, una mención, un comando o mucho más texto)"), told);
    }

    @Test
    void aMessageTheNetworkNoLongerLetsEditIsntEdited() throws Exception {
        Message m = sent("creo q sí");
        answer = t -> {
            svc.chat(CHAT).setFeatures(app.unichat.model.RoomFeatures.parse(
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("id", "x").put("edit", 0)));
            return t.replace(" q ", " que ");
        };
        fixer.fix(m).get();
        assertFalse(m.edited());
        assertEquals(List.of("Ya no se puede editar este mensaje en su red"), told);
    }
}

package app.unichat.ui.timeline;

import app.unichat.assist.ReplyWriter;
import app.unichat.model.Message;
import app.unichat.service.ScriptedChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correcting before sending: each message waits in its chat's queue while it's corrected, all of them at once, and goes
 * corrected in the order they were written; any can be cancelled (nothing goes) or sent as written.
 */
class SendQueueTest {
    private static final String CHAT = "!family:demo";
    private ScriptedChatService svc;
    private final List<String> told = new CopyOnWriteArrayList<>();
    /** The corrections asked for, run when the test says (as the AI answers, in any order). */
    private final Map<String, Runnable> running = new ConcurrentHashMap<>();
    private final AtomicInteger cancelled = new AtomicInteger();
    /** How many corrections were asked for: each one's number, from 0. */
    private final AtomicInteger asked = new AtomicInteger();
    private volatile ReplyWriter.Failure failure;
    private volatile String added = "";
    /** What the AI answers instead of a correction (null: as when it was cancelled); unset: it corrects. */
    private volatile boolean answersNothing;
    private volatile boolean sendFails;
    private volatile java.util.concurrent.CountDownLatch sendGate;
    private SendQueue queue;

    @BeforeEach
    void start() {
        svc = new ScriptedChatService() {
            @Override
            public java.util.concurrent.CompletableFuture<Void> sendText(String chatId, String text, String replyToId) {
                if (sendGate != null) {
                    try {
                        sendGate.await(); // a slow network
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (sendFails) return java.util.concurrent.CompletableFuture.failedFuture(new java.io.IOException("sin red"));
                return super.sendText(chatId, text, replyToId);
            }
        };
        queue = new SendQueue(svc, () -> new AiReplyButton.Writer() {
            @Override public String write(String prompt) throws ReplyWriter.Failure {
                if (failure != null) throw failure;
                if (answersNothing) return null;
                return prompt.substring(prompt.indexOf(">\n") + 2, prompt.indexOf("\n</")).replace(" q ", " que ") + added;
            }

            @Override public void cancel() { cancelled.incrementAndGet(); }
        }, told::add, r -> running.put("#" + asked.getAndIncrement(), r));
    }

    @AfterEach
    void stop() {
        svc.stop();
        app.unichat.assist.AiSettings.setKeepThread(true);
    }

    private List<String> sent() {
        return svc.messages(CHAT).stream().filter(Message::outgoing).map(Message::body).toList();
    }

    private void answer(int which) {
        running.remove("#" + which).run();
    }

    @Test
    void aMessageWaitsWhileCorrectedAndGoesCorrected() {
        int before = sent().size();
        SendQueue.Pending p = queue.submit(CHAT, "creo q sí", null);
        assertEquals(before, sent().size(), "not gone yet");
        assertEquals(List.of(p), queue.waiting(CHAT));
        answer(0);
        assertEquals("creo que sí", sent().getLast());
        assertEquals(List.of(), queue.waiting(CHAT));
        assertEquals(List.of(), told, "nothing to say: it went as it should");
    }

    @Test
    void manyAreCorrectedAtOnceAndGoInTheOrderWritten() {
        int before = sent().size();
        queue.submit(CHAT, "uno q sí", null);
        queue.submit(CHAT, "dos q no", null);
        queue.submit(CHAT, "tres q tal", null);
        assertEquals(3, running.size(), "all three being corrected at once");
        answer(2);
        answer(1);
        assertEquals(before, sent().size(), "the first one isn't ready: the later ones wait for it");
        answer(0);
        assertEquals(List.of("uno que sí", "dos que no", "tres que tal"), sent().subList(before, sent().size()));
    }

    @Test
    void cancellingStopsItsCorrectionAndNothingGoesTheNextOnesStillDo() {
        int before = sent().size();
        SendQueue.Pending first = queue.submit(CHAT, "uno q sí", null);
        queue.submit(CHAT, "dos q no", null);
        answer(1);
        queue.cancel(first);
        assertEquals(1, cancelled.get(), "its correction is stopped");
        assertEquals(List.of("dos que no"), sent().subList(before, sent().size()), "the next one no longer waits for it");
        answer(0); // the cancelled one's answer comes anyway
        assertEquals(1, sent().size() - before, "and sends nothing");
    }

    @Test
    void oneCanBeSentAsWrittenWithoutWaiting() {
        int before = sent().size();
        SendQueue.Pending p = queue.submit(CHAT, "creo q sí", null);
        queue.sendNow(p);
        assertEquals(List.of("creo q sí"), sent().subList(before, sent().size()));
        assertEquals(1, cancelled.get());
        answer(0);
        assertEquals(1, sent().size() - before, "once");
    }

    @Test
    void aFailedCorrectionSendsItAsWrittenAndSaysWhy() {
        failure = new ReplyWriter.Failure("sin red");
        queue.submit(CHAT, "creo q sí", null);
        answer(0);
        assertEquals("creo q sí", sent().getLast(), "never lost");
        assertEquals(List.of("Se envió sin corregir: sin red"), told);
    }

    @Test
    void aCorrectionThatAddsWhatWasntThereSendsItAsWritten() {
        added = " https://malo.example/x";
        queue.submit(CHAT, "creo q sí", null);
        answer(0);
        assertEquals("creo q sí", sent().getLast());
        assertTrue(told.getFirst().startsWith("Se envió sin corregir: la corrección añadía"), told.toString());
    }

    @Test
    void aReplyGoesAsAReplyAndEachChatHasItsOwnQueue() throws Exception {
        Message answered = svc.messages(CHAT).getFirst();
        queue.submit(CHAT, "creo q sí", answered.id());
        queue.submit("!mom:demo", "listo q sí", null);
        assertEquals(1, queue.waiting(CHAT).size());
        assertEquals(1, queue.waiting("!mom:demo").size());
        answer(1);
        assertEquals("listo que sí", svc.messages("!mom:demo").getLast().body(), "another chat doesn't wait for this one");
        answer(0);
        assertEquals(answered.id(), svc.messages(CHAT).getLast().replyToId());
    }

    @Test
    void itsListenersHearEachChange() {
        AtomicInteger changes = new AtomicInteger();
        queue.addListener(changes::incrementAndGet);
        SendQueue.Pending p = queue.submit(CHAT, "creo q sí", null);
        queue.cancel(p);
        assertTrue(changes.get() >= 2);
    }

    /** The newest message someone else wrote in the chat. */
    private Message lastOfOthers() {
        return svc.messages(CHAT).stream().filter(m -> !m.outgoing()).toList().getLast();
    }

    @Test
    void whenOthersWroteMeanwhileItAnswersWhatWasLastSaidWhenItWasWritten() {
        svc.receive(CHAT, "¿vienes mañana?");
        Message asked = lastOfOthers();
        queue.submit(CHAT, "creo q sí", null);
        svc.receive(CHAT, "¿y a qué hora?"); // the chat goes on while it's corrected
        answer(0);
        Message mine = svc.messages(CHAT).getLast();
        assertEquals("creo que sí", mine.body());
        assertEquals(asked.id(), mine.replyToId(), "it says what it answers, so the thread reads right");
    }

    @Test
    void whenNobodyWroteMeanwhileItGoesAsItIs() {
        svc.receive(CHAT, "¿vienes mañana?");
        queue.submit(CHAT, "creo q sí", null);
        answer(0);
        assertNull(svc.messages(CHAT).getLast().replyToId(), "right after the question already: no quote");
    }

    @Test
    void anAnswerChosenIsKeptAndItCanBeTurnedOff() {
        svc.receive(CHAT, "¿vienes mañana?");
        Message chosen = svc.messages(CHAT).getFirst();
        queue.submit(CHAT, "creo q sí", chosen.id());
        svc.receive(CHAT, "¿y a qué hora?");
        answer(0);
        assertEquals(chosen.id(), svc.messages(CHAT).getLast().replyToId(), "the person's own choice");

        app.unichat.assist.AiSettings.setKeepThread(false);
        queue.submit(CHAT, "dijo q no", null);
        svc.receive(CHAT, "¿y entonces?");
        answer(1);
        assertNull(svc.messages(CHAT).getLast().replyToId(), "turned off");
    }

    @Test
    void aNetworkWithoutRepliesGetsNone() {
        svc.receive(CHAT, "¿vienes mañana?");
        svc.chat(CHAT).setFeatures(app.unichat.model.RoomFeatures.parse(
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("id", "x").put("reply", 0)));
        queue.submit(CHAT, "creo q sí", null);
        svc.receive(CHAT, "¿y a qué hora?");
        answer(0);
        assertNull(svc.messages(CHAT).getLast().replyToId());
    }

    @Test
    void whatWasWrittenIsHandedOnWhenTheCorrectionChangedIt() {
        List<String> handed = new CopyOnWriteArrayList<>();
        queue.onCorrected((chat, sent, written) -> handed.add(chat + "|" + sent + "|" + written));
        queue.submit(CHAT, "creo q sí", null);
        answer(0);
        queue.submit(CHAT, "todo bien", null);
        answer(1);
        failure = new ReplyWriter.Failure("sin red");
        queue.submit(CHAT, "dijo q no", null);
        answer(2);
        assertEquals(List.of(CHAT + "|creo que sí|creo q sí"), handed, "only where it changed");
    }

    @Test
    void anEmoteIsCorrectedWithoutItsCommandAndGoesAsAnEmote() {
        queue.submit(CHAT, "/me dice q sí", null);
        answer(0);
        Message m = svc.messages(CHAT).getLast();
        assertEquals("dice que sí", m.body());
        assertEquals(app.unichat.model.MessageType.EMOTE, m.type());
    }

    @Test
    void sendingAsWrittenWinsOverACorrectionAlreadyWaitingBehindAnother() {
        int before = sent().size();
        queue.submit(CHAT, "uno q sí", null);
        SendQueue.Pending second = queue.submit(CHAT, "dos q no", null);
        answer(1); // corrected, waiting behind the first
        queue.sendNow(second); // the person doesn't want the AI's version
        answer(0);
        assertEquals(List.of("uno que sí", "dos q no"), sent().subList(before, sent().size()));
    }

    @Test
    void anAnswerOfNothingIsSentAsWrittenNeverACrash() {
        answersNothing = true;
        queue.submit(CHAT, "creo q sí", null);
        answer(0);
        assertEquals("creo q sí", sent().getLast());
        assertEquals(List.of("Se envió sin corregir: no hubo respuesta"), told);
    }

    @Test
    void aSendThatFailsIsTold() {
        sendFails = true;
        queue.submit(CHAT, "creo q sí", null);
        answer(0);
        assertEquals(List.of("No se pudo enviar: sin red"), told);
    }

    @Test
    void itNeverAnswersAMessageDeletedMeanwhile() {
        svc.receive(CHAT, "¿vienes mañana?");
        Message asked = lastOfOthers();
        queue.submit(CHAT, "creo q sí", null);
        asked.markDeleted(); // what it answered was taken back
        svc.receive(CHAT, "olvídalo"); // and the chat went on
        answer(0);
        assertNull(svc.messages(CHAT).getLast().replyToId(), "never a quote of what's gone");
    }

    @Test
    void whatItWouldAnswerIsSaidForTheRowToShow() {
        svc.receive(CHAT, "¿vienes mañana?");
        SendQueue.Pending p = queue.submit(CHAT, "creo q sí", null);
        assertTrue(p.wouldAnswer() != null && p.wouldAnswer().contains("¿vienes mañana?"), String.valueOf(p.wouldAnswer()));
        assertNull(queue.submit(CHAT, "dijo q no", svc.messages(CHAT).getFirst().id()).wouldAnswer(), "the person chose");
        app.unichat.assist.AiSettings.setKeepThread(false);
        assertNull(queue.submit(CHAT, "ok", null).wouldAnswer(), "turned off");
    }

    @Test
    void whatElseIsSentWaitsItsTurnBehindTheTexts() {
        List<String> order = new CopyOnWriteArrayList<>();
        queue.inOrder(CHAT, () -> order.add("foto antes"));
        assertEquals(List.of("foto antes"), order, "nothing waiting: at once");
        queue.submit(CHAT, "mira esto q chévere", null);
        queue.inOrder(CHAT, () -> order.add("foto"));
        assertEquals(List.of("foto antes"), order, "behind the text being corrected");
        answer(0);
        assertEquals(List.of("foto antes", "foto"), order);
        assertEquals("mira esto que chévere", sent().getLast(), "the text went first");
    }

    @Test
    void endingSendsEverythingWaitingAsWrittenInOrder() {
        int before = sent().size();
        queue.submit(CHAT, "uno q sí", null);
        queue.submit(CHAT, "dos q no", null);
        queue.submit("!mom:demo", "tres q tal", null);
        var _ = queue.drain();
        assertEquals(List.of("uno q sí", "dos q no"), sent().subList(before, sent().size()), "never lost on quitting");
        assertEquals("tres q tal", svc.messages("!mom:demo").getLast().body());
        assertEquals(3, cancelled.get(), "the corrections stopped");
        running.values().forEach(Runnable::run); // their answers come anyway
        assertEquals(2, sent().size() - before, "and send nothing more");
    }

    @Test
    void whileOneIsBeingSentTheQueueCanStillBeLookedAtAndAddedTo() throws Exception {
        sendGate = new java.util.concurrent.CountDownLatch(1);
        queue.submit(CHAT, "uno q sí", null);
        Thread sending = Thread.ofPlatform().start(() -> answer(0)); // its send waits on the network
        while (sending.getState() != Thread.State.WAITING) Thread.onSpinWait();
        SendQueue.Pending second = queue.submit(CHAT, "dos", null); // returns: the queue isn't held by the send
        assertEquals(List.of(second), queue.waiting(CHAT), "and can be looked at");
        sendGate.countDown();
        sending.join(); // lesson-ok: test-future-join (a Thread, which JUnit's timeout can interrupt)
    }

    /** A service with a queue, and the queue's listener holding the service, as a window does; then let go. */
    private static java.lang.ref.WeakReference<app.unichat.service.ChatService> queuedThenDropped() {
        // A bare service: nothing else in the run holds one (a demo service is held by its own timers).
        app.unichat.service.ChatService bare = (app.unichat.service.ChatService) java.lang.reflect.Proxy.newProxyInstance(
                SendQueueTest.class.getClassLoader(), new Class<?>[] {app.unichat.service.ChatService.class},
                (proxy, method, args) -> method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                        : method.getName().equals("equals") ? proxy == args[0] : null);
        SendQueue.of(bare).addListener(() -> { var _ = bare; }); // a window listening holds the service it shows
        return new java.lang.ref.WeakReference<>(bare);
    }

    @Test
    void aQueueNeverKeepsItsServiceAlive() {
        java.lang.ref.WeakReference<app.unichat.service.ChatService> gone = queuedThenDropped();
        while (gone.get() != null) { // collected once nothing but the queue's registry holds it
            System.gc(); // NOPMD: DoNotCallGarbageCollectionExplicitly, it measures what is kept after a collection
            Thread.onSpinWait();
        }
    }
}

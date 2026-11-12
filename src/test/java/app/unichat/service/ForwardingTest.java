package app.unichat.service;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forwarding: copies of the messages, in their order, to every chat chosen, saying where they came from only if asked. */
class ForwardingTest {
    private static final String FROM = "!familia:demo", TO = "!mama:demo", ALSO = "!andres:demo";

    private static MockChatService svc() {
        MockChatService svc = new MockChatService();
        svc.noAutoReplies();
        return svc;
    }

    private static List<Message> sentTo(MockChatService svc, String chatId, int before) {
        List<Message> all = svc.messages(chatId);
        return all.subList(before, all.size());
    }

    @Test
    void copiesGoInOrderToEveryChatAndSayNothingByDefault() throws Exception {
        MockChatService svc = svc();
        List<Message> picked = svc.messages(FROM).stream().filter(m -> !m.isMedia() && m.type() == MessageType.TEXT).limit(3).toList();
        int to = svc.messages(TO).size(), also = svc.messages(ALSO).size();
        Forwarding.Job job = Forwarding.send(svc, picked, List.of(TO, ALSO), m -> null, true);
        job.done().get();
        assertEquals(List.of(6, 6, 0), List.of(job.total(), job.sent(), job.failed()), "each message to each chat, counted");
        for (List<Message> sent : List.of(sentTo(svc, TO, to), sentTo(svc, ALSO, also))) {
            assertEquals(picked.stream().map(Message::body).toList(), sent.stream().map(Message::body).toList());
            assertTrue(sent.stream().allMatch(Message::outgoing), "they go as mine");
            assertTrue(sent.stream().allMatch(m -> m.replyToId() == null), "a forward answers nothing");
        }
    }

    @Test
    void aNoteGoesBeforeEachRunOfMessagesItDescribes() throws Exception {
        MockChatService svc = svc();
        List<Message> picked = svc.messages(FROM).stream().filter(m -> m.type() == MessageType.TEXT).limit(4).toList();
        Function<Message, String> bySender = m -> "↪ " + m.senderName();
        int to = svc.messages(TO).size();
        Forwarding.send(svc, picked, List.of(TO), bySender, true).done().get();
        List<String> expected = new java.util.ArrayList<>();
        String last = null;
        for (Message m : picked) {
            if (!bySender.apply(m).equals(last)) expected.add(last = bySender.apply(m));
            expected.add(m.body());
        }
        assertEquals(expected, sentTo(svc, TO, to).stream().map(Message::body).toList());
    }

    @Test
    void theNoteIsPlainTextWhateverNameItHolds() throws Exception {
        MockChatService svc = svc();
        Message m = svc.messages(FROM).stream().filter(x -> x.type() == MessageType.TEXT).findFirst().orElseThrow();
        int to = svc.messages(TO).size();
        Forwarding.send(svc, List.of(m), List.of(TO), x -> "↪ Reenviado de *Ana*", true).done().get();
        Message note = sentTo(svc, TO, to).getFirst();
        assertEquals("↪ Reenviado de *Ana*", note.body());
        assertNull(note.html(), "a stranger's name with marks isn't formatted");
    }

    @Test
    void aCopyKeepsWhatItShowsButNotWhoOrWhatItAnswered() {
        Message from = new Message("$1", FROM, "@ana:demo", "Ana", false, java.time.Instant.EPOCH);
        from.setType(MessageType.IMAGE);
        from.setBody("la foto");
        from.setFileName("foto.jpg");
        from.setMediaUrl("mxc://s/foto");
        from.setMimeType("image/jpeg");
        from.setSize(800, 600);
        from.setFileSize(1234);
        from.setReplyToId("$0");
        Message into = new Message("~1", TO, "@me:demo", "Yo", true, java.time.Instant.now());
        Forwarding.copy(from, into);
        assertEquals(List.of(MessageType.IMAGE, "la foto", "foto.jpg", "mxc://s/foto", "image/jpeg", 800, 600, 1234L),
                List.of(into.type(), into.body(), into.fileName(), into.mediaUrl(), into.mimeType(), into.width(), into.height(), into.fileSize()));
        assertNull(into.replyToId());
    }

    @Test
    void cancelledWhatsLeftDoesntGo() throws Exception {
        CompletableFuture<Void> held = new CompletableFuture<>();
        int[] asked = {0};
        MockChatService slow = new MockChatService() {
            @Override public CompletableFuture<Void> forward(String chatId, Message message, boolean gifs) {
                asked[0]++;
                return held.thenCompose(v -> super.forward(chatId, message, gifs));
            }
        };
        slow.noAutoReplies();
        List<Message> picked = slow.messages(FROM).stream().filter(m -> m.type() == MessageType.TEXT).limit(3).toList();
        Forwarding.Job job = Forwarding.send(slow, picked, List.of(TO), m -> null, true);
        assertEquals(picked.getFirst(), job.current(), "says which one is going");
        job.cancel();
        held.complete(null);
        job.done().get();
        assertEquals(1, asked[0], "only the one going when cancelled");
        assertEquals(1, job.sent());
        assertTrue(job.finished() && job.cancelled());
    }
}

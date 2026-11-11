package app.unichat.service;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The demo with nobody acting on their own and no pauses: the test plays the others in the chat (writing, typing, the
 * history the server still has).
 */
public final class ScriptedChatService extends MockChatService {
    private final Map<String, Integer> olderToLoad = new ConcurrentHashMap<>();

    public ScriptedChatService() {
        noAutoReplies();
        skipDemoPauses();
    }

    /** Someone else in {@code chatId} writes {@code text}, as if it arrived from the network. */
    public void receive(String chatId, String text) {
        incoming(chats.get(chatId), someoneElse(chatId), text, true);
    }

    /** Someone else in {@code chatId} starts or stops writing. */
    public void othersTyping(String chatId, boolean typing) {
        setTyping(chats.get(chatId), someoneElse(chatId), typing);
    }

    /** Message {@code id} now says {@code text}, whoever wrote it (an answer streaming in, an embed arriving). */
    public void rewrite(String chatId, String id, String text) {
        for (Message m : messages(chatId)) {
            if (m.id().equals(id)) m.setBody(text);
        }
        fireMessages(chatId);
    }

    /** The next history load of {@code chatId} brings {@code count} older messages, of a few lines each. */
    public void olderToLoad(String chatId, int count) {
        olderToLoad.put(chatId, count);
    }

    @Override
    public CompletableFuture<Boolean> loadOlder(String chatId) {
        Integer count = olderToLoad.remove(chatId);
        if (count == null) return CompletableFuture.completedFuture(false);
        List<Message> list = messages.get(chatId);
        Member from = someoneElse(chatId);
        synchronized (list) {
            Instant first = list.isEmpty() ? Instant.now() : list.getFirst().timestamp();
            List<Message> older = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Message m = new Message(UUID.randomUUID().toString(), chatId, from.id(), from.displayName(), false,
                        first.minusSeconds(60L * (count - i)));
                m.setBody("Antiguo " + i + "\n".repeat(i % 3) + "más");
                older.add(m);
            }
            list.addAll(0, older);
        }
        fireMessages(chatId);
        return CompletableFuture.completedFuture(true);
    }

    private Member someoneElse(String chatId) {
        Chat chat = chats.get(chatId);
        return chat.members().stream().filter(mb -> !mb.id().equals(ME)).findFirst().orElseThrow();
    }
}

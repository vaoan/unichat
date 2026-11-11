package app.unichat.service;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The demo with nobody acting on their own and no pauses: the test plays the others in the chat (writing, typing, the
 * history the server still has) and decides when pictures and files finish downloading.
 */
public final class ScriptedChatService extends MockChatService {
    private final Map<String, Integer> olderToLoad = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> downloads = CompletableFuture.completedFuture(null);

    public ScriptedChatService() {
        noAutoReplies();
        skipDemoPauses();
    }

    /** Someone else in {@code chatId} writes {@code text}, as if it arrived from the network. */
    public void receive(String chatId, String text) {
        incoming(chats.get(chatId), someoneElse(chatId), text, true);
    }

    /** Someone else in {@code chatId} sends a message {@code fill} makes (a photo, a sticker, a file…); its id. */
    public String receive(String chatId, Consumer<Message> fill) {
        Member from = someoneElse(chatId);
        Message m = new Message(UUID.randomUUID().toString(), chatId, from.id(), from.displayName(), false, Instant.now());
        fill.accept(m);
        append(chats.get(chatId), m);
        return m.id();
    }

    /**
     * Downloads wait from now on, until the returned gate says how they end: what's asked meanwhile shows as it does on
     * a slow connection, and the test then sees it arrive (or fail).
     */
    public Gate holdDownloads() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        downloads = gate;
        progress.clear();
        return new Gate(gate, progress);
    }

    /** Who listens to the held downloads' progress. */
    private final List<java.util.function.DoubleConsumer> progress = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Ends the downloads held by {@link #holdDownloads}, or says how far they got meanwhile. */
    public static final class Gate {
        private final CompletableFuture<Void> gate;
        private final List<java.util.function.DoubleConsumer> listeners;

        private Gate(CompletableFuture<Void> gate, List<java.util.function.DoubleConsumer> listeners) {
            this.gate = gate;
            this.listeners = listeners;
        }

        public void release() { gate.complete(null); }

        public void fail() { gate.completeExceptionally(new java.io.IOException("download failed (test)")); }

        /** The held downloads have {@code fraction} of their file (0 to 1). */
        public void report(double fraction) { listeners.forEach(l -> l.accept(fraction)); }
    }

    @Override
    public CompletableFuture<Path> media(String url, int width, int height, java.util.function.DoubleConsumer listener) {
        if (!downloads.isDone()) progress.add(listener);
        return media(url, width, height);
    }

    @Override
    public CompletableFuture<Path> media(String url, int width, int height) {
        CompletableFuture<Void> gate = downloads;
        return gate.isDone() && !gate.isCompletedExceptionally() ? super.media(url, width, height)
                : gate.thenCompose(x -> super.media(url, width, height));
    }

    /** Someone else in {@code chatId} starts or stops writing. */
    public void othersTyping(String chatId, boolean typing) {
        setTyping(chats.get(chatId), someoneElse(chatId), typing);
    }

    /** Message {@code id} now says {@code text}, whoever wrote it (an answer streaming in). */
    public void rewrite(String chatId, String id, String text) {
        rewrite(chatId, id, m -> m.setBody(text));
    }

    /** Message {@code id} changed by {@code change}, whoever wrote it (an embed or a link preview arriving later). */
    public void rewrite(String chatId, String id, Consumer<Message> change) {
        for (Message m : messages(chatId)) {
            if (m.id().equals(id)) change.accept(m);
        }
        fireMessages(chatId);
    }

    /**
     * The next history load of {@code chatId} brings {@code count} older messages, of a few lines each, once the
     * returned gate lets it through: the test sees it asked for ({@link #historyAsked}), then decides when it comes.
     */
    public Gate olderToLoad(String chatId, int count) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        olderToLoad.put(chatId, count);
        history = gate;
        return new Gate(gate, List.of());
    }

    /** Whether the window asked {@code chatId}'s history since {@link #olderToLoad}. */
    public boolean historyAsked(String chatId) {
        return asked.contains(chatId);
    }

    private volatile CompletableFuture<Void> history = CompletableFuture.completedFuture(null);
    private final java.util.Set<String> asked = ConcurrentHashMap.newKeySet();

    @Override
    public CompletableFuture<Boolean> loadOlder(String chatId) {
        Integer count = olderToLoad.remove(chatId);
        if (count == null) return CompletableFuture.completedFuture(false);
        asked.add(chatId);
        return history.thenApply(x -> {
            prepend(chatId, count);
            return true;
        });
    }

    private void prepend(String chatId, int count) {
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
    }

    private Member someoneElse(String chatId) {
        Chat chat = chats.get(chatId);
        return chat.members().stream().filter(mb -> !mb.id().equals(ME)).findFirst().orElseThrow();
    }
}

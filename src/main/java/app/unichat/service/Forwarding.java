package app.unichat.service;

import app.unichat.model.Message;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Forwarding messages to other chats, of any network. No bridge forwards natively from Matrix (it has no forward event),
 * so a forward is a copy sent as one's own: what the message shows, never what it answered or who wrote it, unless a
 * note says so.
 */
public final class Forwarding {
    private Forwarding() {}

    /** What a forward of {@code from} carries, into a new message of one's own. */
    public static void copy(Message from, Message into) {
        into.setType(from.type());
        into.setBody(from.body());
        into.setHtml(from.html());
        into.setMediaUrl(from.mediaUrl());
        into.setFileName(from.fileName());
        into.setMimeType(from.mimeType());
        into.setFileSize(from.fileSize());
        into.setSize(from.width(), from.height());
        into.setMediaInfo(from.mediaInfo());
    }

    /** What a forward going on has done: how many went and failed of how many, which chat and message now. Read on any thread. */
    public static final class Job {
        private final int total;
        private final List<String> chatIds;
        private final java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile Message current;
        private volatile String currentChat;
        private volatile boolean cancelled;

        Job(int total, List<String> chatIds) {
            this.total = total;
            this.chatIds = List.copyOf(chatIds);
        }

        public int total() { return total; }
        public int sent() { return sent.get(); }
        public int failed() { return failed.get(); }
        public List<String> chatIds() { return chatIds; }
        /** The message going now (null when none). */
        public Message current() { return current; }
        public String currentChat() { return currentChat; }
        public boolean cancelled() { return cancelled; }
        public boolean finished() { return done.isDone(); }
        /** Completes when the last one went (or failed), or after a cancel when the one going ends. */
        public CompletableFuture<Void> done() { return done; }

        /** What's left doesn't go; the one going ends as it does. */
        public void cancel() {
            cancelled = true;
            changed();
        }

        /** Runs on any thread after each change. */
        public void addListener(Runnable r) { listeners.add(r); }

        private void changed() {
            for (Runnable r : listeners) r.run();
        }
    }

    /**
     * Sends {@code messages}, in their order, to each of {@code chatIds}, one at a time. {@code note} gives the line that
     * says where a message came from (null: none); it goes before a message only when it differs from the one before,
     * so a run from the same person carries one. One that fails is counted and the rest still go. {@code gifs}: as
     * {@link ChatMessaging#forward}.
     */
    public static Job send(ChatMessaging svc, List<Message> messages, List<String> chatIds, Function<Message, String> note, boolean gifs) {
        Job job = new Job(messages.size() * chatIds.size(), chatIds);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String chatId : chatIds) {
            String[] last = {null};
            for (Message m : messages) {
                String line = note.apply(m);
                boolean noted = line != null && !line.equals(last[0]);
                last[0] = line;
                chain = chain.thenCompose(v -> {
                    if (job.cancelled) return CompletableFuture.completedFuture(null);
                    job.current = m;
                    job.currentChat = chatId;
                    job.changed();
                    // The line as plain text, like a forward: it holds names strangers chose ("*Ana*" isn't bold), and pings no one.
                    return (noted ? svc.forward(chatId, plain(chatId, line), false) : CompletableFuture.<Void>completedFuture(null))
                            .handle((x, err) -> logged(err))
                            .thenCompose(x -> svc.forward(chatId, m, gifs))
                            .handle((x, err) -> {
                                if (err != null) System.err.println("Forward failed: " + err);
                                (err == null ? job.sent : job.failed).incrementAndGet();
                                return null;
                            });
                });
            }
        }
        chain.whenComplete((v, err) -> {
            job.current = null;
            job.done.complete(null);
            job.changed();
        });
        return job;
    }

    private static Message plain(String chatId, String text) {
        Message m = new Message("forward-note", chatId, "", "", true, java.time.Instant.now());
        m.setBody(text);
        return m;
    }

    /** A line that couldn't go is logged: the messages still go, and only they count. */
    private static Void logged(Throwable err) {
        if (err != null) System.err.println("Forward note failed: " + err);
        return null;
    }

}

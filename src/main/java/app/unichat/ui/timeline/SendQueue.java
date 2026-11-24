package app.unichat.ui.timeline;

import app.unichat.assist.AiSettings;
import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.service.ChatService;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Correcting before sending (Ajustes → Escribir con IA): each text sent from the box waits in its chat's queue while
 * it's corrected ({@link Correction}), all of them at once, and goes corrected in the order it was written; what else
 * the chat sends meanwhile (a file, a voice note) waits its turn behind them ({@link #inOrder}). Nothing waits for it:
 * the box, sending more, other chats. Any can be cancelled (stopped, nothing goes) or sent as written. A correction that
 * fails goes as written, said; quitting sends what's waiting as written ({@link #drain}): a message is never lost. If
 * others wrote while it waited, it answers what was last said when it was written ({@link AiSettings#keepThread}).
 * One per service, so a window rebuilt (a language change) shows and controls the same queue.
 */
public final class SendQueue {
    /**
     * A text waiting: which chat, what it says, what it answers (the person's choice, or null), and what it would answer
     * if the chat moves on meanwhile ("Ana: «¿vienes?»"; null when it won't).
     */
    record Pending(long n, String chatId, String text, @Nullable String replyToId, @Nullable String wouldAnswer) {}

    /** Something to send in its turn: a text being corrected ({@link Text}) or another send ({@link Action}). */
    private sealed interface Entry permits Text, Action {
        boolean isReady();
    }

    /** A text, being corrected until {@code ready}; {@code lastSaid}: what others said last when it was written, if any. */
    private static final class Text implements Entry {
        final Pending p;
        final AiReplyButton.Writer writer;
        final @Nullable Message lastSaid;
        @Nullable String ready;

        Text(Pending p, AiReplyButton.Writer writer, @Nullable Message lastSaid) {
            this.p = p;
            this.writer = writer;
            this.lastSaid = lastSaid;
        }

        @Override public boolean isReady() {
            return ready != null;
        }
    }

    /** Another send (a file, a voice note), ready as soon as what's before it has gone. */
    private record Action(Runnable send) implements Entry {
        @Override public boolean isReady() {
            return true;
        }
    }

    /**
     * Each service's queue, held weakly: a queue holds its listeners (the windows showing it) and they their service,
     * so a queue held here kept every service and window for good (a run of UI tests ran out of memory). A queue with
     * something waiting is held by the threads correcting it; one with nothing waiting has nothing to lose.
     */
    private static final Map<ChatService, java.lang.ref.WeakReference<SendQueue>> BY_SERVICE = new WeakHashMap<>();
    /** How much of what it would answer a row shows. */
    private static final int ANSWER_PREVIEW = 40;

    private final ChatService svc;
    private volatile Supplier<AiReplyButton.Writer> writers;
    private volatile Consumer<String> tell;
    private final Executor runner;
    private final Map<String, Deque<Entry>> byChat = new HashMap<>();
    /** What's ready and taken out of its queue, in the order it goes; sent outside the queue's lock, one at a time. */
    private final Deque<Entry> outbox = new ArrayDeque<>();
    private final Object sending = new Object();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong next = new AtomicLong();
    private volatile Corrected corrected = (chat, sent, written) -> { };

    /** A text went corrected: what went, and what had been written (kept as its first version). */
    @FunctionalInterface
    interface Corrected {
        void went(String chatId, String sent, String written);
    }

    /**
     * @param tell what came of it, in the person's words (only what didn't go as asked)
     * @param runner where each correction runs: a thread of its own each (tests run them when they want)
     */
    SendQueue(ChatService svc, Supplier<AiReplyButton.Writer> writers, Consumer<String> tell, Executor runner) {
        this.svc = svc;
        this.writers = writers;
        this.tell = tell;
        this.runner = runner;
    }

    /** {@code svc}'s queue: the person's AI program, a daemon thread per correction; told to the window that shows it. */
    public static synchronized SendQueue of(ChatService svc) {
        java.lang.ref.WeakReference<SendQueue> held = BY_SERVICE.get(svc);
        SendQueue q = held == null ? null : held.get();
        if (q == null) {
            q = new SendQueue(svc, AiReplyButton::fixWriter, System.err::println,
                    r -> Thread.ofPlatform().daemon().name("ai-fix-queued").start(r));
            BY_SERVICE.put(svc, new java.lang.ref.WeakReference<>(q));
        }
        return q;
    }

    /** Where what came of it is told: the window showing the queue now. */
    void tellTo(Consumer<String> to) {
        this.tell = to;
    }

    /** Tests: a stand-in for the AI program. */
    void useWriter(Supplier<AiReplyButton.Writer> w) {
        writers = w;
    }

    /** Told each text that went different from how it was written. */
    void onCorrected(Corrected c) {
        corrected = c;
    }

    void addListener(Runnable r) {
        listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    /** The texts waiting in {@code chatId}, in the order they'll go. Any thread. */
    synchronized List<Pending> waiting(String chatId) {
        Deque<Entry> q = byChat.get(chatId);
        return q == null ? List.of() : q.stream().filter(Text.class::isInstance).map(e -> ((Text) e).p).toList();
    }

    /** {@code text} waits in {@code chatId}'s queue while it's corrected; returns at once. */
    Pending submit(String chatId, String text, @Nullable String replyToId) {
        Message last = lastSaid(chatId);
        String would = replyToId == null && last != null && AiSettings.keepThread()
                ? last.senderName() + ": «" + preview(last.body()) + "»" : null;
        Pending p = new Pending(next.getAndIncrement(), chatId, text, replyToId, would);
        Text e = new Text(p, writers.get(), last);
        synchronized (this) {
            byChat.computeIfAbsent(chatId, c -> new ArrayDeque<>()).add(e);
        }
        changed();
        runner.execute(() -> corrected(e, correct(e)));
        return p;
    }

    /** Sends with {@code send} in {@code chatId}'s turn: at once with nothing waiting, else after what's waiting. */
    void inOrder(String chatId, Runnable send) {
        synchronized (this) {
            Deque<Entry> q = byChat.get(chatId);
            if (q != null && !q.isEmpty()) {
                q.add(new Action(send));
                return;
            }
        }
        send.run();
    }

    /** Stops {@code p}'s correction and takes it out: nothing of it goes. */
    void cancel(Pending p) {
        Text e;
        synchronized (this) {
            e = find(p);
            Deque<Entry> q = byChat.get(p.chatId()); // there whenever find() found it
            if (e != null && q != null) q.remove(e);
        }
        if (e == null) return;
        e.writer.cancel();
        flush(p.chatId());
    }

    /** Sends {@code p} as written, as soon as what came before it in its chat has gone, whatever its correction said. */
    void sendNow(Pending p) {
        Text e;
        synchronized (this) {
            e = find(p);
            if (e == null) return;
            e.ready = p.text(); // first: a correction ending now finds it decided
        }
        e.writer.cancel();
        flush(p.chatId());
    }

    /**
     * Quitting or logging out: every correction stops and what's waiting goes now, as written (what was corrected
     * already, corrected), in order. Done once the service has them all.
     */
    public CompletableFuture<Void> drain() {
        List<String> chats;
        List<Text> stopping = new ArrayList<>();
        synchronized (this) {
            chats = new ArrayList<>(byChat.keySet());
            for (Deque<Entry> q : byChat.values()) {
                for (Entry e : q) {
                    if (e instanceof Text t && t.ready == null) {
                        t.ready = t.p.text();
                        stopping.add(t);
                    }
                }
            }
        }
        stopping.forEach(e -> e.writer.cancel());
        List<CompletableFuture<?>> sent = new ArrayList<>();
        for (String c : chats) sent.addAll(flush(c));
        return CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new));
    }

    /** The corrected text to send (with "/me " back if it had it), or what was written, said why. */
    private String correct(Text e) {
        String text = e.p.text();
        String said = Message.withoutEmoteCommand(text);
        String command = text.substring(0, text.length() - said.length());
        Correction.Result r = Correction.of(e.writer, said);
        if (r.failure() == null) return command + r.text();
        boolean undecided;
        synchronized (this) {
            undecided = e.ready == null && find(e.p) != null;
        }
        if (undecided) tell.accept(I18n.t("sendQueue.asWritten", r.failure()));
        return text;
    }

    private void corrected(Text e, String text) {
        synchronized (this) {
            if (e.ready == null) e.ready = text; // "send as written", or quitting, may have come first
        }
        flush(e.p.chatId());
    }

    /**
     * Takes what's ready at the head of {@code chatId}'s queue, in order, and sends it outside the queue's lock (the
     * service tells every listener as it sends: the box and the rows mustn't wait on that). One sender at a time, from
     * one outbox: the order stays the one they were taken in. What the sends give back, to wait for.
     */
    private List<CompletableFuture<?>> flush(String chatId) {
        synchronized (this) {
            Deque<Entry> q = byChat.get(chatId);
            while (q != null && !q.isEmpty() && q.getFirst().isReady()) outbox.add(q.removeFirst());
        }
        changed();
        List<CompletableFuture<?>> sent = new ArrayList<>();
        synchronized (sending) {
            Entry e;
            while ((e = nextOut()) != null) sent.add(send(e));
        }
        return sent;
    }

    private synchronized @Nullable Entry nextOut() {
        return outbox.poll();
    }

    private CompletableFuture<?> send(Entry entry) {
        if (!(entry instanceof Text e)) {
            ((Action) entry).send().run();
            return CompletableFuture.completedFuture(null);
        }
        String chatId = e.p.chatId();
        String ready = java.util.Objects.requireNonNull(e.ready, "only what's ready leaves its queue");
        if (!ready.equals(e.p.text())) corrected.went(chatId, Message.withoutEmoteCommand(ready), Message.withoutEmoteCommand(e.p.text()));
        return svc.sendText(chatId, ready, replyTo(e)).exceptionally(t -> {
            tell.accept(I18n.t("sendQueue.sendFailed", Errors.message(t)));
            return null;
        });
    }

    /**
     * What {@code e} answers: the person's choice; else, if someone else wrote after what was last said when it was
     * written (and that's still there), that; else nothing.
     */
    private @Nullable String replyTo(Text e) {
        String chosen = e.p.replyToId();
        if (chosen != null) return chosen;
        Message lastSaid = e.lastSaid;
        if (lastSaid == null || !AiSettings.keepThread()) return null;
        Chat chat = svc.chat(e.p.chatId());
        if (chat == null || !(chat.features().replies() || chat.features().isUnknown())) return null;
        List<Message> all = svc.messages(e.p.chatId());
        int at = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(lastSaid.id())) at = i;
        if (at < 0 || all.get(at).deleted()) return null;
        for (Message m : all.subList(at + 1, all.size())) if (saidByOthers(m)) return lastSaid.id();
        return null;
    }

    /** The newest message someone else wrote in {@code chatId}; null with none. */
    private @Nullable Message lastSaid(String chatId) {
        List<Message> all = svc.messages(chatId);
        for (int i = all.size() - 1; i >= 0; i--) if (saidByOthers(all.get(i))) return all.get(i);
        return null;
    }

    private static boolean saidByOthers(Message m) {
        return !m.outgoing() && !m.deleted() && m.type() != app.unichat.model.MessageType.SYSTEM;
    }

    private static String preview(String text) {
        String one = text.replace('\n', ' ');
        return one.length() > ANSWER_PREVIEW ? one.substring(0, ANSWER_PREVIEW) + "…" : one;
    }

    private synchronized @Nullable Text find(Pending p) {
        Deque<Entry> q = byChat.get(p.chatId());
        if (q == null) return null;
        for (Entry e : q) if (e instanceof Text t && p.equals(t.p)) return t;
        return null;
    }

    private void changed() {
        for (Runnable r : listeners) r.run();
    }
}

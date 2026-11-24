package app.unichat.ui.timeline;

import app.unichat.assist.WritingStyle;
import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.service.ChatService;
import app.unichat.support.Errors;
import app.unichat.support.I18n;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * "Corregir con IA" on a message of mine already sent: the same correction as ⌘O ({@link Correction}), done apart from
 * the message box on a thread of its own, then the message edited by itself. Nothing waits for it: the box, sending and
 * ⌘O go on; each message being corrected shows it ({@link #fixing}, told to {@link #addListener}). Several at once,
 * each its own; the same one asked again while going on starts nothing.
 */
final class MessageFixer implements ChatService.Listener {
    /** What's remembered as sent here and not yet corrected, per chat: the newest few. */
    private static final int EXPECTED = 20;
    private final ChatService svc;
    private volatile Supplier<AiReplyButton.Writer> writers;
    private final Consumer<String> tell;
    private final Executor runner;
    private final Set<String> working = ConcurrentHashMap.newKeySet();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /**
     * A text sent from this computer's box, and my messages the chat had before it went: one of those saying the same
     * (sent from the phone earlier) is never it.
     */
    private record Expected(String text, Set<String> before) {}

    /** Texts sent from this computer's message box, by chat, to correct by themselves once the server has them. */
    private final java.util.Map<String, java.util.Deque<Expected>> expected = new java.util.HashMap<>();

    /**
     * @param tell what came of it, in the person's words (a notice)
     * @param runner where the AI runs: a thread of its own each (tests run it where they want)
     */
    MessageFixer(ChatService svc, Supplier<AiReplyButton.Writer> writers, Consumer<String> tell, Executor runner) {
        this.svc = svc;
        this.writers = writers;
        this.tell = tell;
        this.runner = runner;
    }

    /** The app's: the person's AI program, a daemon thread per message. */
    MessageFixer(ChatService svc, Consumer<String> tell) {
        this(svc, AiReplyButton::fixWriter, tell, r -> Thread.ofPlatform().daemon().name("ai-fix-message").start(r));
    }

    /** Tests: a stand-in for the AI program. */
    void useWriter(Supplier<AiReplyButton.Writer> w) {
        writers = w;
    }

    /** Whether message {@code messageId} is being corrected now. Any thread. */
    boolean fixing(String messageId) {
        return working.contains(messageId);
    }

    /** Runs on any thread when a correction starts or ends: each message's indicator. */
    void addListener(Runnable r) {
        listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    /**
     * {@code text} was just sent in {@code chatId} from the message box, with correcting by itself on (Ajustes → Escribir
     * con IA): it's corrected once the server has it ({@link #messagesChanged}). Only what's sent here: what's sent from
     * the phone, or another computer with it on, isn't this one's to edit (two would correct the same message).
     */
    synchronized void expect(String chatId, String text) {
        Set<String> before = new java.util.HashSet<>();
        for (Message m : svc.messages(chatId)) if (m.outgoing()) before.add(m.id());
        java.util.Deque<Expected> texts = expected.computeIfAbsent(chatId, c -> new java.util.ArrayDeque<>());
        texts.addLast(new Expected(text.strip(), before));
        if (texts.size() > EXPECTED) texts.removeFirst();
    }

    /** Whether {@code text} sent in {@code chatId} is waiting to be corrected by itself. */
    synchronized boolean expecting(String chatId, String text) {
        java.util.Deque<Expected> texts = expected.get(chatId);
        return texts != null && texts.stream().anyMatch(e -> e.text().equals(text.strip()));
    }

    /**
     * A message expected ({@link #expect}) is in the chat: corrected quietly once the server has it (while it's being
     * sent its id is a temporary one); forgotten if it didn't go.
     */
    @Override
    public void messagesChanged(String chatId) {
        List<Message> ready = new java.util.ArrayList<>();
        synchronized (this) {
            java.util.Deque<Expected> texts = expected.get(chatId);
            if (texts == null || texts.isEmpty()) return;
            List<Message> all = svc.messages(chatId);
            for (Message m : all.subList(Math.max(0, all.size() - EXPECTED), all.size())) {
                if (!m.outgoing() || m.deleted()) continue;
                Expected e = texts.stream().filter(x -> x.text().equals(m.body().strip()) && !x.before().contains(m.id()))
                        .findFirst().orElse(null);
                if (e == null || m.status() == app.unichat.model.MessageStatus.SENDING) continue;
                texts.remove(e);
                if (m.status() != app.unichat.model.MessageStatus.FAILED) ready.add(m);
            }
        }
        for (Message m : ready) { var _ = fix(m, true); }
    }

    /** Corrects {@code m} and edits it, every outcome told; done once edited or left as it was. */
    CompletableFuture<Void> fix(Message m) {
        return fix(m, false);
    }

    /**
     * {@code quiet}: corrected by itself after sending (Ajustes → Escribir con IA): only a failure is told, not a notice
     * for every message.
     */
    CompletableFuture<Void> fix(Message m, boolean quiet) {
        if (!working.add(m.id())) return CompletableFuture.completedFuture(null);
        changed();
        String original = m.body();
        CompletableFuture<Void> done = new CompletableFuture<>();
        runner.execute(() -> {
            CompletableFuture<Void> edited;
            Correction.Result r = Correction.of(writers.get(), original);
            String fixed = r.text(); // null exactly when it failed
            if (fixed != null) edited = apply(m, original, fixed, quiet);
            else {
                tell.accept(I18n.t("messageFix.failed", r.failure()));
                edited = CompletableFuture.completedFuture(null);
            }
            var _ = edited.whenComplete((v, e) -> {
                working.remove(m.id());
                changed();
                done.complete(null);
            });
        });
        return done;
    }

    /** Edits {@code m} to {@code corrected} if it's still what was corrected, and its network still lets it. */
    private CompletableFuture<Void> apply(Message m, String original, String corrected, boolean quiet) {
        Message now = svc.messages(m.chatId()).stream().filter(x -> x.id().equals(m.id())).findFirst().orElse(null);
        if (now == null || now.deleted() || !now.body().equals(original)) {
            tell.accept(I18n.t("messageFix.changed")); // edited meanwhile, or deleted: what the person did stays
            return CompletableFuture.completedFuture(null);
        }
        if (corrected.equals(original.strip())) {
            if (!quiet) tell.accept(I18n.t("messageFix.nothing"));
            return CompletableFuture.completedFuture(null);
        }
        Chat chat = svc.chat(m.chatId());
        if (chat == null || !chat.features().canEdit(m.timestamp(), Instant.now())) {
            tell.accept(I18n.t("messageFix.tooLate"));
            return CompletableFuture.completedFuture(null);
        }
        return svc.editMessage(m.chatId(), m.id(), corrected).handle((v, e) -> {
            if (e != null) tell.accept(I18n.t("messageFix.failed", Errors.message(e)));
            else {
                // What the person wrote is learned from, not what the AI made of it.
                Thread.ofVirtual().start(() -> WritingStyle.get().writtenByAi(corrected));
            }
            if (e == null && !quiet) {
                tell.accept(I18n.t("messageFix.done"));
                app.unichat.assist.ReadySound.play(); // as ⌘O's: the person may be looking elsewhere
            }
            return null;
        });
    }

    private void changed() {
        for (Runnable r : listeners) r.run();
    }
}

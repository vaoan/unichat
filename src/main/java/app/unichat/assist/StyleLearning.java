package app.unichat.assist;

import app.unichat.model.Message;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * When learning runs (after a draft, and once a corrected draft is sent): in the background, one at a time; asked
 * again while one runs, it runs once more after it, with the newest request (corrections are shared, so none is
 * lost). {@link StyleLearner} itself calls the program only when there's something new.
 */
public final class StyleLearning {
    private static final StyleLearning SHARED = new StyleLearning(WritingStyle::get);

    private final Supplier<WritingStyle> store;
    private boolean running;
    private Job next;

    private record Job(String chatId, List<Message> chat, StyleLearner.Program program, CompletableFuture<Void> done) {}

    StyleLearning(Supplier<WritingStyle> store) {
        this.store = store;
    }

    /** The one every composer uses. */
    public static StyleLearning shared() { return SHARED; }

    /**
     * Learns from {@code chat} (a copy, taken by the caller) in the background. The future completes when a run that
     * started after this request ends.
     */
    public CompletableFuture<Void> request(String chatId, List<Message> chat, StyleLearner.Program program) {
        Job job;
        synchronized (this) {
            if (running) {
                CompletableFuture<Void> done = next == null ? new CompletableFuture<>() : next.done();
                next = new Job(chatId, chat, program, done);
                return done;
            }
            running = true;
            job = new Job(chatId, chat, program, new CompletableFuture<>());
        }
        start(job);
        return job.done();
    }

    private void start(Job job) {
        Thread.ofVirtual().name("learn-style").start(() -> {
            try {
                new StyleLearner(store.get(), job.program()).learn(job.chatId(), job.chat());
            } catch (RuntimeException e) {
                System.err.println("Writing style not learned: " + e.getClass().getSimpleName());
            } finally {
                finished(job);
            }
        });
    }

    private void finished(Job job) {
        Job then;
        synchronized (this) {
            then = next;
            next = null;
            running = then != null;
        }
        job.done().complete(null);
        if (then != null) start(then);
    }
}

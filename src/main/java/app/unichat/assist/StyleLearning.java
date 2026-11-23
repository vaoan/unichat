package app.unichat.assist;

import app.unichat.model.Message;
import app.unichat.support.Seen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * When learning runs (after a draft, and once a corrected draft is sent): in the background, one at a time; asked
 * again while one runs, it runs once more after it, with the newest request (corrections are shared, so none is
 * lost). {@link StyleLearner} itself calls the program only when there's something new. While nobody can see Unichat
 * ({@link Seen}) a request waits, only the newest per chat, and runs as soon as it's seen again.
 */
public final class StyleLearning {
    private static final StyleLearning SHARED = new StyleLearning(WritingStyle::get);

    private final Supplier<WritingStyle> store;
    private boolean running;
    private Job next;
    /** Requests made while unseen, the newest per chat; its future is the one every earlier request got. */
    private final Map<String, Job> held = new LinkedHashMap<>();

    private record Job(String chatId, List<Message> chat, StyleLearner.Program program, CompletableFuture<Void> done) {}

    StyleLearning(Supplier<WritingStyle> store) {
        this.store = store;
        // Called under Seen's lock: only hands over to a thread of its own.
        Seen.get().addListener(seen -> {
            if (seen) Thread.ofVirtual().name("learn-style-held").start(this::releaseHeld);
        });
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
            if (!Seen.get().now()) {
                Job before = held.get(chatId);
                Job waiting = new Job(chatId, chat, program, before == null ? new CompletableFuture<>() : before.done());
                held.put(chatId, waiting);
                return waiting.done();
            }
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

    /** Seen again: each held request goes in as if made now, and its future completes with the run that covers it. */
    private void releaseHeld() {
        List<Job> jobs;
        synchronized (this) {
            jobs = new ArrayList<>(held.values());
            held.clear();
        }
        for (Job j : jobs) submit(j);
    }

    private void submit(Job j) {
        synchronized (this) {
            if (running) {
                if (next == null) {
                    next = j;
                } else {
                    CompletableFuture<Void> shared = next.done();
                    shared.whenComplete((v, e) -> j.done().complete(null));
                    next = new Job(j.chatId(), j.chat(), j.program(), shared);
                }
                return;
            }
            running = true;
        }
        start(j);
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

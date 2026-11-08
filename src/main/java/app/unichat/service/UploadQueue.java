package app.unichat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Many files sent one after another (a batch): they arrive in order, one at a time, and the upload bar shows how far it
 * got. Reliable: a file that doesn't go up is tried again, waiting longer each time, for as long as it takes (only one
 * that is no longer on disk is given up); the person can cancel. The batch is kept on disk and picks up where it was if
 * Unichat closes. One per service, so it outlives the window being rebuilt (a language change). Listeners run on any thread.
 */
public final class UploadQueue {
    /** A breath between files: the networks behind the bridges take a burst of sends as spam. */
    static final long PAUSE_MS = 1000;
    /** The first wait before trying a file again; it doubles each time, up to {@link #MAX_RETRY_MS}. */
    static final long RETRY_MS = 30_000;
    static final long MAX_RETRY_MS = 5 * 60_000;
    /** On start, a batch left from before waits this long: the server and the bridges are still coming up. */
    static final long RESUME_MS = 10_000;

    private static final Map<ChatMessaging, UploadQueue> QUEUES = new WeakHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One file to send to one chat. */
    public record Upload(String chatId, Path file, String replyToId, boolean asDocument) {}

    /** Runs {@code r} after {@code ms} (tests run it at once, or keep it). */
    interface Later {
        void run(long ms, Runnable r);
    }

    /** Where the current file is: going up to our server, waiting for the network to take it, or waiting to try again. */
    public enum Phase { UPLOADING, DELIVERING, RETRYING }

    /**
     * What the bar shows. {@code current} is the file going up, null once nothing is left; {@code progress} is how
     * much of it went up (0 to 1). {@code total} counts every file of the batch, sent, failed or still waiting.
     * {@code retries}: how many times the current file has failed so far; {@code retryAt}: when it's tried again
     * (epoch ms, while {@link Phase#RETRYING}). {@code etaMs}: the time left, from how long the last files took; -1 unknown.
     */
    public record Status(Upload current, int sent, int failed, int total, double progress, int retries, Phase phase,
                         long retryAt, long etaMs) {
        public static final Status IDLE = new Status(null, 0, 0, 0, 0, 0, Phase.UPLOADING, 0, -1);

        public boolean idle() {
            return total == 0;
        }

        /** Nothing left to send, and a batch to report on. */
        public boolean finished() {
            return current == null && total > 0;
        }

        /** The position of the file going up, from 1. */
        public int position() {
            return Math.min(total, sent + failed + 1);
        }

        /** The whole batch, 0 to 1. */
        public double overall() {
            return total == 0 ? 0 : Math.min(1, (sent + failed + (current == null ? 0 : progress)) / total);
        }
    }

    /** How many of the last files' times the estimate averages: recent enough to follow a slower network. */
    static final int ETA_SAMPLES = 20;

    /** The time left for {@code remaining} files, from how long each of the last ones took; -1 with none yet. */
    static long eta(java.util.Collection<Long> durations, int remaining) {
        if (durations.isEmpty() || remaining <= 0) return remaining <= 0 ? 0 : -1;
        long sum = 0;
        for (long d : durations) sum += d;
        return sum / durations.size() * remaining;
    }

    private final ChatMessaging svc;
    private final Later later;
    /** Where the batch is kept; null: nowhere (tests). */
    private final Path store;
    private final Deque<Upload> pending = new ArrayDeque<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Upload current;
    /** The current file stops being tried (the batch was cancelled). */
    private boolean abandoned;
    /** The current file failed and waits to be tried again. */
    private boolean waiting;
    private int retries;
    private long retryAt;
    /** When the current file started, and how long the last ones took (with their retries and pauses). */
    private long fileStarted;
    private final Deque<Long> durations = new ArrayDeque<>();
    private int sent;
    private int failed;
    private int total;
    private double progress;

    UploadQueue(ChatMessaging svc, Later later, Path store) {
        this.svc = svc;
        this.later = later;
        this.store = store;
        List<Upload> left = load(store);
        if (!left.isEmpty()) later.run(RESUME_MS, () -> addAll(left));
    }

    /** The queue for {@code svc}, kept in {@code store} (a file in the data folder; the demo has its own). */
    public static synchronized UploadQueue of(ChatMessaging svc, Path store) {
        return QUEUES.computeIfAbsent(svc, s -> new UploadQueue(s,
                (ms, r) -> CompletableFuture.runAsync(r, CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS)), store));
    }

    /** The queue for {@code svc} if there's one already (the composer's and the bar's are the same). */
    public static synchronized UploadQueue of(ChatMessaging svc) {
        UploadQueue q = QUEUES.get(svc);
        return q != null ? q : of(svc, null);
    }

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    public void removeListener(Runnable l) {
        listeners.remove(l);
    }

    public synchronized Status status() {
        Phase phase = waiting ? Phase.RETRYING : progress >= 1 ? Phase.DELIVERING : Phase.UPLOADING;
        int left = total - sent - failed;
        return new Status(current, sent, failed, total, progress, retries, phase, retryAt, eta(durations, left));
    }

    /** Adds files to the batch; the first starts at once if nothing is going up. */
    public void addAll(List<Upload> uploads) {
        if (uploads.isEmpty()) return;
        boolean start;
        synchronized (this) {
            if (current == null && pending.isEmpty()) { // a finished batch was still on show: the new one counts from zero
                sent = 0;
                failed = 0;
                total = 0;
                durations.clear();
                fileStarted = 0;
            }
            pending.addAll(uploads);
            total += uploads.size();
            start = current == null;
            save();
        }
        if (start) next();
        else fire();
    }

    /** Drops the files that haven't started; the one going up is not tried again if it fails (if it's waiting to, it stops). */
    public void cancel() {
        boolean stopNow;
        synchronized (this) {
            total -= pending.size();
            pending.clear();
            abandoned = current != null;
            stopNow = waiting;
            waiting = false;
            save();
        }
        if (stopNow) done(false);
        else fire();
    }

    /** The finished batch was seen: the bar goes away. */
    public void dismiss() {
        synchronized (this) {
            if (current != null || !pending.isEmpty()) return;
            sent = 0;
            failed = 0;
            total = 0;
        }
        fire();
    }

    private void next() {
        Upload u;
        synchronized (this) {
            u = pending.poll();
            current = u;
            abandoned = false;
            waiting = false;
            retries = 0;
            progress = 0;
            if (u != null && fileStarted == 0) fileStarted = System.currentTimeMillis();
        }
        fire();
        if (u != null) attempt(u);
    }

    private void attempt(Upload u) {
        CompletableFuture<Void> sending;
        try {
            sending = svc.sendFile(u.chatId(), u.file(), u.replyToId(), u.asDocument(), p -> progress(u, p));
        } catch (RuntimeException e) {
            sending = CompletableFuture.failedFuture(e);
        }
        sending.whenComplete((v, err) -> {
            long wait;
            synchronized (this) {
                wait = err == null || abandoned || !Files.isReadable(u.file()) ? -1 : retryDelay(++retries);
                if (wait >= 0) progress = 0;
            }
            if (wait >= 0) {
                synchronized (this) {
                    waiting = true;
                    retryAt = System.currentTimeMillis() + wait;
                }
                System.err.println("Batch upload: a file didn't go up (try " + retries + "), again in " + wait / 1000 + " s: " + err);
                fire();
                later.run(wait, () -> retry(u));
            } else {
                done(err == null);
            }
        });
    }

    private void retry(Upload u) {
        synchronized (this) {
            if (current != u || !waiting) return; // cancelled meanwhile
            waiting = false;
        }
        attempt(u);
    }

    /** 30 s, 1 min, 2 min, 4 min, then every 5 min. */
    static long retryDelay(int failures) {
        long ms = RETRY_MS << Math.min(failures - 1, 10);
        return Math.min(ms, MAX_RETRY_MS);
    }

    private void done(boolean ok) {
        boolean more;
        synchronized (this) {
            if (ok) sent++;
            else failed++;
            long now = System.currentTimeMillis();
            if (fileStarted > 0) durations.addLast(now - fileStarted);
            while (durations.size() > ETA_SAMPLES) durations.removeFirst();
            fileStarted = pending.isEmpty() ? 0 : now; // the pause before the next counts towards it
            if (!ok) System.err.println("Batch upload: a file was given up (cancelled, or no longer on disk)");
            // During the pause the bar already shows the next file, at nothing sent yet.
            current = pending.peek();
            retries = 0;
            progress = 0;
            more = current != null;
            save();
        }
        if (more) {
            fire();
            later.run(PAUSE_MS, this::next);
        } else {
            next();
        }
    }

    /** Tells the listeners only when another whole percent went up: an upload reports every few kilobytes. */
    private void progress(Upload u, double p) {
        synchronized (this) {
            if (current != u || (int) (p * 100) == (int) (progress * 100)) return;
            progress = p;
        }
        fire();
    }

    private void fire() {
        for (Runnable l : listeners) l.run();
    }

    // ------------------------------------------------------------------ on disk

    /** The file going up and those waiting, so a restart sends them. Call holding the lock; small and quick. */
    private void save() {
        if (store == null) return;
        List<Upload> left = new ArrayList<>();
        // During the pause between files the next one is already "current" while still first in line: once only.
        if (current != null && !abandoned && current != pending.peek()) left.add(current);
        left.addAll(pending);
        try {
            if (left.isEmpty()) {
                Files.deleteIfExists(store);
                return;
            }
            Path tmp = store.resolveSibling(store.getFileName() + ".tmp");
            JSON.writeValue(tmp.toFile(), left.stream().map(Saved::of).toList());
            Files.move(tmp, store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Could not keep the batch on disk: " + e);
        }
    }

    private static List<Upload> load(Path store) {
        if (store == null || !Files.isRegularFile(store)) return List.of();
        try {
            List<Saved> saved = JSON.readValue(store.toFile(), new TypeReference<List<Saved>>() { });
            return saved.stream().map(Saved::upload).toList();
        } catch (IOException e) {
            System.err.println("Could not read the batch left from before: " + e);
            return List.of();
        }
    }

    /** As kept on disk: plain strings, so a path reads the same on any computer it's copied to. */
    record Saved(String chatId, String file, String replyToId, boolean asDocument) {
        static Saved of(Upload u) {
            return new Saved(u.chatId(), u.file().toString(), u.replyToId(), u.asDocument());
        }

        Upload upload() {
            return new Upload(chatId, Path.of(file), replyToId, asDocument);
        }
    }
}

package app.unichat.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Downloads of pinned files: {@code target} only appears, whole, if the checksum matches. */
public final class Downloads {
    /** One for every download: each client keeps its own threads and connections. */
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    private Downloads() {}

    /** See {@link #fetch(String, long, String, Path, String, DoubleConsumer, Consumer)}. */
    public static void fetch(String url, long size, String sha256, Path target, String what, DoubleConsumer progress)
            throws IOException, InterruptedException {
        fetch(url, size, sha256, target, what, progress, part -> { });
    }

    /**
     * Blocking.
     *
     * @param size       in bytes; 0 when unknown, and {@code progress} isn't told
     * @param what       for error messages, already in the language in use ({@code I18n.t("download.what.voiceModel")})
     * @param progress   gets 0..1
     * @param beforeMove gets the whole, checked file just before it takes {@code target}'s name
     */
    public static void fetch(String url, long size, String sha256, Path target, String what, DoubleConsumer progress,
                             Consumer<Path> beforeMove) throws IOException, InterruptedException {
        fetch(url, size, sha256, target, what, progress, beforeMove, STALL);
    }

    /** No byte for this long and a download gives up: a stalled one held the local server's lock for good. */
    static final Duration STALL = Duration.ofSeconds(60);

    private static final java.util.concurrent.ScheduledExecutorService GUARD =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "download-stall-guard");
                t.setDaemon(true);
                return t;
            });

    static void fetch(String url, long size, String sha256, Path target, String what, DoubleConsumer progress,
                      Consumer<Path> beforeMove, Duration stall) throws IOException, InterruptedException {
        // The request's timeout bounds the wait for the headers; the guard below, each wait for the body.
        HttpResponse<InputStream> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(STALL).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close(); // left open, the connection stayed busy with a body nobody reads
            throw new IOException(I18n.t("download.http", res.statusCode(), what));
        }
        MessageDigest md = sha256();
        try (InputStream in = new DigestInputStream(res.body(), md)) {
            SafeFile.at(target, ".part").beforeMove(part -> {
                if (!HexFormat.of().formatHex(md.digest()).equals(sha256)) throw new IOException(I18n.t("download.corrupt", what));
                beforeMove.accept(part);
            }).write(out -> copy(in, out, stall, what, done -> {
                if (size > 0) progress.accept(Math.min(1, (double) done / size));
            }));
        }
    }

    /**
     * Copies {@code in} to {@code out}, giving up when no byte arrives for {@code stall}: the guard interrupts the read
     * (the HTTP body's read waits interruptibly; closing it doesn't wake that wait).
     */
    @SuppressWarnings("Finally") // a stall ends the copy with whatever the interrupt threw: the stall is what to say
    private static void copy(InputStream in, java.io.OutputStream out, Duration stall, String what,
                             java.util.function.LongConsumer done) throws IOException {
        Thread reader = Thread.currentThread();
        java.util.concurrent.atomic.AtomicLong last = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        Object lock = new Object();
        boolean[] state = {false, false}; // stalled, finished
        long every = Math.clamp(stall.toMillis() / 4, 1, 1000);
        java.util.concurrent.ScheduledFuture<?> guard = GUARD.scheduleWithFixedDelay(() -> {
            synchronized (lock) {
                if (state[1] || System.nanoTime() - last.get() < stall.toNanos()) return;
                state[0] = true;
                reader.interrupt();
            }
        }, every, every, java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                last.set(System.nanoTime());
                out.write(buf, 0, n);
                total += n;
                done.accept(total);
            }
        } catch (IOException e) { // NOPMD: ExceptionAsFlowControl, one failure path for a stall and a read error
            if (!stalledAndDone(lock, state, guard)) throw e;
        } finally { // NOPMD: DoNotThrowExceptionInFinally, a stall replaces whatever the interrupt threw (as Error Prone's Finally)
            if (stalledAndDone(lock, state, guard)) throw new IOException(I18n.t("download.stalled", what));
        }
    }

    /** Stops the guard; whether it found the download stalled (its interrupt cleared: the caller isn't left with it). */
    private static boolean stalledAndDone(Object lock, boolean[] state, java.util.concurrent.ScheduledFuture<?> guard) {
        synchronized (lock) {
            state[1] = true;
        }
        guard.cancel(false);
        if (state[0]) Thread.interrupted();
        return state[0];
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package app.unichat.support;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A response body from a stranger's server, read with a byte limit and a deadline. {@code HttpRequest.timeout} stops at
 * the headers: a server trickling a byte a minute held the reading thread for good. At the deadline the body is closed,
 * which wakes a read waiting on it ({@code HttpClient}'s stream throws "closed").
 */
public final class BoundedBody extends FilterInputStream {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(Threads.daemon("body-deadline"));

    private final long maxBytes;
    private final ScheduledFuture<?> deadline;
    private volatile boolean late;
    private long total;

    public BoundedBody(InputStream body, long maxBytes, Duration within) {
        super(body);
        this.maxBytes = maxBytes;
        this.deadline = TIMER.schedule(this::expire, within.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void expire() {
        late = true;
        try {
            in.close();
        } catch (IOException ignored) {
            // Closing is all there is to do; the reader finds out by itself.
        }
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
        if (late) throw new IOException(I18n.t("download.tooSlow"));
        int n;
        try {
            n = super.read(b, off, len);
        } catch (IOException e) {
            if (late) throw new IOException(I18n.t("download.tooSlow"), e);
            throw e;
        }
        if (late) throw new IOException(I18n.t("download.tooSlow"));
        if (n > 0) total += n;
        if (total > maxBytes) throw new IOException(I18n.t("download.tooBig"));
        return n;
    }

    @Override public long skip(long n) throws IOException {
        return n <= 0 ? 0 : Math.max(0, read(new byte[(int) Math.min(n, 8192)]));
    }

    @Override public void close() throws IOException {
        deadline.cancel(false);
        super.close();
    }
}

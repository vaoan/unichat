package app.unichat.ui.media;

/**
 * Memory for the animations being decoded at once. Opening the sticker panel decoded many big ones together, and the
 * peak (each frame whole before it was made small, and what the collector hadn't swept yet) restarted Unichat for
 * memory, though what stayed was 206 MB. A decode takes what it may need while it runs and gives it back; past the
 * budget it waits its turn. One bigger than the whole budget goes alone. Never on the FX thread: it may wait.
 */
final class DecodeBudget {
    static final DecodeBudget SHARED = new DecodeBudget(96L << 20);

    private final long limit;
    private long used;
    private int waiting;

    DecodeBudget(long limit) {
        this.limit = limit;
    }

    /** What a decode holds; closing it gives it back. */
    final class Lease implements AutoCloseable {
        private final long bytes;
        private boolean closed;

        private Lease(long bytes) {
            this.bytes = bytes;
        }

        @Override
        public void close() {
            synchronized (DecodeBudget.this) {
                if (closed) return;
                closed = true;
                used -= bytes;
                DecodeBudget.this.notifyAll();
            }
        }
    }

    /** Takes {@code bytes} (at most the whole budget), waiting while it doesn't fit beside the decodes going on. */
    synchronized Lease take(long bytes) throws InterruptedException {
        long b = Math.min(Math.max(0, bytes), limit);
        waiting++;
        notifyAll();
        try {
            while (used > 0 && used + b > limit) wait();
        } finally {
            waiting--;
        }
        used += b;
        return new Lease(b);
    }

    synchronized long used() { return used; }

    /** For tests: waits until {@code count} decodes are waiting their turn. */
    synchronized void awaitWaiting(int count) throws InterruptedException {
        while (waiting < count) wait();
    }
}

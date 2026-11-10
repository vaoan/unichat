package app.unichat.support;

/**
 * Saves on a daemon thread of its own, never on the caller's (the UI thread, or one holding a lock readers need): the
 * owner asks with {@link #request} after a change, and one {@code round} runs for however many requests came meanwhile.
 * A round copies what to write under the owner's lock and writes outside it; a request during a round asks for one more.
 * {@link #flush} waits for it: whoever ends the app (or a session) must call it, a daemon thread doesn't outlive the JVM.
 * Its state is guarded by the owner's lock, so {@link #flush} may be called holding it: waiting lets it go.
 */
public final class BackgroundWriter {
    private final String name;
    private final Object lock;
    private final Runnable round;
    private boolean pending, running;

    /** @param lock the owner's: what {@code round} takes to copy what it writes */
    public BackgroundWriter(String name, Object lock, Runnable round) {
        this.name = name;
        this.lock = lock;
        this.round = round;
    }

    /** Asks for a round; starts the thread unless one is running (it will see this request). */
    public void request() {
        synchronized (lock) {
            pending = true;
            if (running) return;
            running = true;
            Thread.ofPlatform().daemon().name(name).start(this::run);
        }
    }

    /** Returns once every round asked for so far has run. Blocks, so never on the UI thread during normal use. */
    public void flush() {
        synchronized (lock) {
            boolean interrupted = false;
            while (running) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    interrupted = true; // the last change is worth more than a prompt answer
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void run() {
        boolean more = true;
        while (more) {
            synchronized (lock) {
                pending = false;
            }
            boolean carryOn = false;
            try {
                round.run();
                carryOn = true;
            } catch (RuntimeException e) {
                System.err.println(name + ": " + e);
                carryOn = true;
            } finally {
                // Decided in one step: a request between "nothing more" and "stopped" would start no thread and wait
                // forever. An Error ends this thread (and goes to its handler); the next request starts another.
                synchronized (lock) {
                    more = carryOn && pending;
                    if (!more) {
                        running = false;
                        lock.notifyAll();
                    }
                }
            }
        }
    }
}

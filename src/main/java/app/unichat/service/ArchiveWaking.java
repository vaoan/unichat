package app.unichat.service;

/**
 * Archived chats that rest out of the sync while the archive is closed (archived Discord servers and channels, behind
 * Matrix), and waking them while it's open. The demo has none: nothing rests, nothing wakes.
 */
public interface ArchiveWaking {
    /** Where waking is: Discord servers bringing everything again, then chats catching up on what they missed, then done. */
    enum Step { SERVERS, CHATS, DONE }

    /** {@code done} of {@code total} in this step. */
    record Waking(Step step, int done, int total) {
        public double fraction() { return total <= 0 ? 1 : Math.min(1, (double) done / total); }
    }

    /** The archive was opened ({@code true}) or left: what rests wakes, or goes back to rest. */
    default void setArchiveOpen(boolean open) {}

    /** What waking is at; null when nothing has woken in this run. */
    default Waking waking() { return null; }

    /** Told on any thread each time {@link #waking} changes. */
    default void addWakingListener(Runnable listener) {}

    default void removeWakingListener(Runnable listener) {}
}

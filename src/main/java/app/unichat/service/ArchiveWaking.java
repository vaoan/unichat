package app.unichat.service;

/**
 * Archived chats that rest out of the sync while the archive is closed (archived Discord servers and channels, behind
 * Matrix), and waking them while it's open. The demo has none: nothing rests, nothing wakes.
 */
public interface ArchiveWaking {
    /**
     * Where waking is: an archived Discord server bringing everything again (opened in the archive), chats catching up
     * on what they missed, a catch-up that couldn't finish and will try again, or done.
     */
    enum Step { SERVER, CHATS, RETRYING, DONE }

    /** {@code done} of {@code total} chats; {@code server}: the server's name in {@link Step#SERVER}, else null. */
    record Waking(Step step, int done, int total, String server) {
        public Waking(Step step, int done, int total) { this(step, done, total, null); }

        public double fraction() { return total <= 0 ? 1 : Math.min(1, (double) done / total); }
    }

    /** The archive was opened ({@code true}) or left: what rests wakes, or goes back to rest. */
    default void setArchiveOpen(boolean open) { /* nothing by default */ }

    /** A chat was opened while the archive is open: an archived server, or a channel in one, brings everything again. */
    default void archivedChatOpened(String chatId) { /* nothing by default */ }

    /** The chat shown in the window: it never rests, however it was opened. */
    default void setOpenChat(String chatId) { /* nothing by default */ }

    /** What waking is at; null when nothing has woken in this run. */
    default Waking waking() { return null; }

    /** Told on any thread each time {@link #waking} changes. */
    default void addWakingListener(Runnable listener) { /* nothing by default */ }

    default void removeWakingListener(Runnable listener) { /* nothing by default */ }
}

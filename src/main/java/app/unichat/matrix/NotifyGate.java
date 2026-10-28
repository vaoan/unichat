package app.unichat.matrix;

import app.unichat.model.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which incoming messages are news worth a notification. Bridges deliver history as new events (backfill,
 * catching up after a reconnect), so arriving now isn't enough: a message must be recent AND newer than anything
 * already seen in its chat. {@link #isNew} runs on the sync loop; {@link #seen} on any thread.
 */
final class NotifyGate {
    /** How old a message may be and still notify. */
    static final Duration WINDOW = Duration.ofMinutes(5);

    /** Per chat, the newest message time already seen (shown or notified). */
    private final Map<String, Instant> seenUpTo = new ConcurrentHashMap<>();

    /** Records that everything in {@code chatId} up to {@code time} was already seen (initial sync, catch-up). */
    void seen(String chatId, Instant time) {
        seenUpTo.merge(chatId, time, (a, b) -> a.isAfter(b) ? a : b);
    }

    /** True when {@code m} is news; it then counts as seen, so a repeat or anything older won't notify. */
    boolean isNew(Message m, Instant now) {
        Instant t = m.timestamp();
        Instant seen = seenUpTo.get(m.chatId());
        boolean news = t.isAfter(now.minus(WINDOW)) && (seen == null || t.isAfter(seen));
        seen(m.chatId(), t);
        return news;
    }
}

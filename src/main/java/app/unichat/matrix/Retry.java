package app.unichat.matrix;

import java.time.Duration;
import java.time.Instant;

/**
 * When something that failed with the server or a bridge is tried again: a minute after the first failure, doubling
 * up to an hour, forever (what the person chose stays chosen; it only waits to reach the other side).
 */
record Retry(int failures, Instant at) {
    static final Duration FIRST = Duration.ofMinutes(1);
    static final Duration LAST = Duration.ofHours(1);

    /** After one more failure at {@code now}; {@code previous} is null for the first. */
    static Retry after(Retry previous, Instant now) {
        int failures = previous == null ? 1 : previous.failures + 1;
        Duration wait = FIRST.multipliedBy(1L << Math.min(failures - 1, 10));
        return new Retry(failures, now.plus(wait.compareTo(LAST) > 0 ? LAST : wait));
    }

    boolean due(Instant now) {
        return !now.isBefore(at);
    }
}

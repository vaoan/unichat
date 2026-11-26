package app.unichat.matrix;

import app.unichat.service.Changes;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A clock and a queue of later checks that only move when the test says so: no real time anywhere. */
final class ManualTime {
    private record Timed(Instant due, Runnable task) {}

    final Changes changes = new Changes();
    private final List<Timed> queue = new ArrayList<>();
    private Instant now;

    ManualTime(Instant start) {
        this.now = start;
    }

    synchronized Instant now() { return now; }

    /** What the code under test calls to look again after {@code d}. */
    void later(Duration d, Runnable task) {
        synchronized (this) {
            queue.add(new Timed(now.plus(d), task));
        }
        changes.changed();
    }

    synchronized int scheduled() { return queue.size(); }

    /** Blocks until the code has scheduled a check (it's waiting on the clock). */
    void untilScheduled() throws InterruptedException {
        changes.until("a check scheduled", () -> scheduled() > 0);
    }

    /** Moves the clock and runs, on this thread, every check now due, in order. */
    void advance(Duration d) {
        synchronized (this) {
            now = now.plus(d);
        }
        while (true) {
            Timed next;
            synchronized (this) {
                Instant at = now;
                next = queue.stream().filter(t -> !t.due().isAfter(at)).min(Comparator.comparing(Timed::due)).orElse(null);
                if (next == null) return;
                queue.remove(next);
            }
            next.task().run();
        }
    }
}

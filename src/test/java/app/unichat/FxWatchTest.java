package app.unichat;

import app.unichat.support.Seen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window froze for good (the render thread spinning after an OutOfMemoryError, the UI thread waiting on it) and
 * nothing noticed: the person had to find it and kill it. The watch asks the UI thread for a sign of life; a minute with
 * no answer is a frozen window. Time is the watch's input here, never waited for.
 */
class FxWatchTest {
    private static final long SECOND = 1_000_000_000L;

    @AfterEach
    void seenAgain() {
        Seen.reset(true);
        Thread.interrupted(); // the interrupt that ended a run, even when an assert failed before it was read
    }

    /** The watch's time: moved by each of its sleeps, and {@code then} run after the n-th; it stops after {@code sleeps}. */
    private static final class Clock {
        final List<Long> slept = new ArrayList<>();
        long now;

        void run(FxWatch watch, int sleeps, Runnable afterFirst) {
            watch.run(() -> now, ms -> {
                slept.add(ms);
                now += ms * 1_000_000;
                if (slept.size() == 1) afterFirst.run();
                if (slept.size() == sleeps) throw new InterruptedException();
            });
        }
    }

    @Test
    void aMinuteWithoutAnswerIsAFrozenWindowAndIsToldOnce() {
        List<Runnable> posted = new ArrayList<>();
        AtomicInteger frozen = new AtomicInteger();
        FxWatch watch = new FxWatch(posted::add, frozen::incrementAndGet);

        watch.check(0);
        assertEquals(1, posted.size(), "asks once");
        watch.check(5 * SECOND);
        assertEquals(1, posted.size(), "never piles questions while one is waiting");
        posted.remove(0).run(); // the UI thread answers
        watch.check(10 * SECOND);
        assertEquals(1, posted.size(), "answered: asks again");

        watch.check(69 * SECOND);
        assertEquals(0, frozen.get(), "59 s is still alive");
        watch.check(70 * SECOND);
        watch.check(80 * SECOND);
        assertEquals(1, frozen.get(), "told once");
    }

    @Test
    void timeAsleepIsntTheWindows() {
        List<Runnable> posted = new ArrayList<>();
        AtomicInteger frozen = new AtomicInteger();
        FxWatch watch = new FxWatch(posted::add, frozen::incrementAndGet);
        watch.check(0);
        watch.forget(); // the computer slept with a question waiting
        watch.check(120 * SECOND);
        assertEquals(0, frozen.get());
        assertEquals(2, posted.size(), "asks again from the wake");
    }

    @Test
    void unseenAsksEverySixtySeconds() {
        List<Runnable> posted = new ArrayList<>();
        AtomicInteger frozen = new AtomicInteger();
        FxWatch watch = new FxWatch(posted::add, frozen::incrementAndGet);
        Seen.reset(false);
        Clock clock = new Clock();
        clock.run(watch, 2, () -> { });
        assertEquals(List.of(60_000L, 60_000L), clock.slept, "a minute between looks while nobody sees it");
        assertEquals(1, posted.size(), "one question in that minute");
        assertEquals(1, frozen.get(), "a UI thread that hangs while unseen is still caught, a minute later");

        Seen.reset(true);
        Clock seen = new Clock();
        seen.run(new FxWatch(posted::add, frozen::incrementAndGet), 2, () -> { });
        assertEquals(List.of(5_000L, 5_000L), seen.slept, "every 5 s while seen");
        assertTrue(Thread.interrupted(), "the interrupt that ended it is kept");
    }

    @Test
    void seenAgainForgetsTheLastQuestion() {
        List<Runnable> posted = new ArrayList<>();
        AtomicInteger frozen = new AtomicInteger();
        FxWatch watch = new FxWatch(posted::add, frozen::incrementAndGet);
        Seen.reset(false);
        Clock clock = new Clock();
        // A minute unanswered while unseen (an app that's put to sleep isn't frozen), then seen again before the look.
        clock.run(watch, 2, () -> Seen.reset(true));
        assertEquals(0, frozen.get(), "the question asked before it was seen again doesn't count");
        assertEquals(2, posted.size(), "asks again from being seen");
        assertEquals(List.of(60_000L, 5_000L), clock.slept);
    }
}

package app.unichat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The window froze for good (the render thread spinning after an OutOfMemoryError, the UI thread waiting on it) and
 * nothing noticed: the person had to find it and kill it. The watch asks the UI thread for a sign of life; a minute with
 * no answer is a frozen window. Time is the watch's input here, never waited for.
 */
class FxWatchTest {
    private static final long SECOND = 1_000_000_000L;

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
        watch.awoke(); // the computer slept with a question waiting
        watch.check(120 * SECOND);
        assertEquals(0, frozen.get());
        assertEquals(2, posted.size(), "asks again from the wake");
    }
}

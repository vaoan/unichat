package app.unichat.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WakeWatchTest {
    @Test
    void aNormalTickIsNotSleep() {
        assertFalse(WakeWatch.slept(2_000, 2_000));
        assertFalse(WakeWatch.slept(2_600, 2_500), "a busy machine ticks a bit late");
        assertFalse(WakeWatch.slept(9_000, 9_000), "a very busy one, still awake");
    }

    @Test
    void wallClockFarAheadOfAwakeTimeIsSleep() {
        assertTrue(WakeWatch.slept(3_600_000, 2_000), "macOS: an hour asleep, two seconds awake");
    }

    @Test
    void aTickerThatDidNotRunForLongIsSleep() {
        assertTrue(WakeWatch.slept(3_600_000, 3_600_000), "where awake time keeps counting while asleep");
    }
}

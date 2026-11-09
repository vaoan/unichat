package app.unichat.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WatchdogTest {
    private static final long NOW = 100 * 60_000;

    @Test
    void aHealthyProcessIsLeftAlone() {
        assertNull(Watchdog.reason(new Watchdog.Health(0, -1, 0), NOW));
        assertNull(Watchdog.reason(new Watchdog.Health(2, -1, 4), NOW), "two missed probes, four drops: not yet");
        assertNull(Watchdog.reason(new Watchdog.Health(0, NOW - 9 * 60_000, 0), NOW), "disconnected 9 minutes");
    }

    @Test
    void hungStuckOrFlappingIsRestarted() {
        assertEquals("not responding", Watchdog.reason(new Watchdog.Health(3, -1, 0), NOW));
        assertNotNull(Watchdog.reason(new Watchdog.Health(0, NOW - 10 * 60_000, 0), NOW));
        assertEquals("5 disconnections in 10 min", Watchdog.reason(new Watchdog.Health(0, -1, 5), NOW));
    }

    @Test
    void restartsInARowWaitLongerUpToAnHour() {
        assertEquals(5 * 60_000, Watchdog.cooldown(1));
        assertEquals(10 * 60_000, Watchdog.cooldown(2));
        assertEquals(40 * 60_000, Watchdog.cooldown(4));
        assertEquals(60 * 60_000, Watchdog.cooldown(9));
    }
}

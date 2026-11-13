package app.unichat.support;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {
    private long free = 50L << 30;
    private long clock = 1_000_000;

    @Test
    void underAGigabyteFreeIsLowAndListenersHearOfEachChange() {
        Storage s = new Storage(() -> free, () -> clock);
        AtomicInteger told = new AtomicInteger();
        s.addListener(told::incrementAndGet);
        s.check();
        assertFalse(s.low());
        assertEquals(0, told.get(), "nothing changed");

        free = Storage.LOW_BYTES - 1;
        s.check();
        assertTrue(s.low());
        assertEquals(Storage.LOW_BYTES - 1, s.free());
        s.check();
        assertEquals(1, told.get(), "once per change");

        free = 5L << 30;
        s.check();
        assertFalse(s.low());
        assertEquals(2, told.get());
    }

    @Test
    void aProcessThatCouldntWriteIsFailingForTenMinutesOrUntilItStartsOver() {
        Storage s = new Storage(() -> free, () -> clock);
        s.failed("tuwunel");
        assertEquals(Set.of("tuwunel"), s.failing());
        clock += Storage.FAILING_MS + 1;
        assertEquals(Set.of(), s.failing());

        s.failed("telegram");
        s.startedOver("telegram");
        assertEquals(Set.of(), s.failing());
    }

    @Test
    void anErrorLineSayingTheDiskIsFullIsAFailedWrite() {
        assertTrue(Storage.writeFailed("{\"level\":\"error\",\"error\":\"database or disk is full\",\"message\":\"Failed to save\"}"));
        assertTrue(Storage.writeFailed("{\"level\":\"fatal\",\"error\":\"write /x/facebook.db-wal: no space left on device\"}"));
        assertTrue(Storage.writeFailed("\u001b[31mERROR\u001b[0m tuwunel_database: IO error: No space left on device: While appending to file"));
        assertTrue(Storage.writeFailed("{\"level\":\"error\",\"error\":\"disk I/O error\"}"));
    }

    @Test
    void theSameWordsOutsideAnErrorAreNot() {
        // Message text can reach a bridge's log: only an error line counts, and still only restarts it (with the watchdog's waits).
        assertFalse(Storage.writeFailed("{\"level\":\"info\",\"message\":\"no space left on device\"}"));
        assertFalse(Storage.writeFailed("{\"level\":\"error\",\"message\":\"Error reading from websocket: EOF\"}"));
        assertFalse(Storage.writeFailed("{\"level\":\"info\",\"message\":\"ERROR: no space left on device\"}"), "a JSON line goes by its level");
    }
}

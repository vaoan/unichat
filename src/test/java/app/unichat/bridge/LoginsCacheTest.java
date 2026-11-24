package app.unichat.bridge;

import app.unichat.Present;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The logins kept of each bridge: one format, written by matrix and read by matrix and server. */
class LoginsCacheTest {
    @Test
    void whatIsWrittenReadsBackTheSame() {
        List<LoginsCache.Row> rows = List.of(new LoginsCache.Row("573001112233", "Trabajo", "CONNECTED"),
                new LoginsCache.Row("42", null, null));
        String kept = LoginsCache.format(1763000000000L, rows);
        assertEquals("1763000000000\n573001112233\tTrabajo\tCONNECTED\n42\t\t\n", kept);
        assertEquals(new LoginsCache.Cached(1763000000000L, rows), LoginsCache.parse(kept));
        assertTrue(LoginsCache.hasLogin(kept));
    }

    @Test
    void noLoginsAndNothingKeptAreTold() {
        assertEquals(new LoginsCache.Cached(1763000000000L, List.of()), LoginsCache.parse("1763000000000\n"));
        assertFalse(LoginsCache.hasLogin("1763000000000\n"));
        assertNull(LoginsCache.parse(null));
        assertFalse(LoginsCache.hasLogin(null));
        assertEquals(0, Present.of(LoginsCache.parse("garbage\n1\t\t\n")).at(), "a broken time is stale");
    }
}

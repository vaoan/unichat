package app.unichat.matrix;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendStatusesTest {
    private static final Duration LONG = Duration.ofMinutes(5);

    @Test
    void waitsForTheBridgeToSaySuccess() throws Exception {
        SendStatuses s = new SendStatuses();
        CompletableFuture<Void> f = s.await("$1", LONG);
        s.heard("$1", "PENDING");
        assertFalse(f.isDone(), "still on its way");
        s.heard("$1", "SUCCESS");
        f.get();
    }

    @Test
    void aRefusalFailsAndAStatusHeardBeforeAskingCounts() {
        SendStatuses s = new SendStatuses();
        CompletableFuture<Void> f = s.await("$1", LONG);
        s.heard("$1", "FAIL_RETRIABLE");
        assertThrows(ExecutionException.class, f::get);
        s.heard("$2", "SUCCESS");
        assertTrue(s.await("$2", LONG).isDone(), "the sync can bring it before the send returns");
    }

    @Test
    void aBridgeThatNeverSaysAnythingDoesNotHoldThingsForever() throws Exception {
        SendStatuses s = new SendStatuses();
        s.await("$1", Duration.ofMillis(50)).get();
    }
}

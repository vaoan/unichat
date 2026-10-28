package app.unichat.matrix;

import app.unichat.model.Message;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotifyGateTest {
    private final Instant now = Instant.parse("2026-10-27T20:00:00Z");
    private final NotifyGate gate = new NotifyGate();

    private static Message msg(String id, String chat, Instant at) {
        return new Message(id, chat, "@bob:example.org", "Bob", false, at);
    }

    @Test
    void aRecentMessageIsNewOnce() {
        Message m = msg("$a", "!r", now.minusSeconds(10));
        assertTrue(gate.isNew(m, now));
        assertFalse(gate.isNew(m, now), "the same message again (resent by a bridge) doesn't notify twice");
    }

    @Test
    void oldHistoryNeverNotifies() {
        assertFalse(gate.isNew(msg("$b", "!r", now.minus(Duration.ofDays(2))), now), "a bridge's backfill");
    }

    @Test
    void recentButAlreadySeenDoesNotNotify() {
        // Seen in the initial sync; a bridge catching up resends a message from a minute before that.
        gate.seen("!r", now.minusSeconds(30));
        assertFalse(gate.isNew(msg("$c", "!r", now.minusSeconds(90)), now));
        assertTrue(gate.isNew(msg("$d", "!r", now.minusSeconds(5)), now), "a later one still does");
        assertTrue(gate.isNew(msg("$e", "!other", now.minusSeconds(60)), now), "other chats keep their own mark");
    }

    @Test
    void anOlderMessageArrivingAfterANewerOneIsHistory() {
        assertTrue(gate.isNew(msg("$f", "!r", now.minusSeconds(5)), now));
        assertFalse(gate.isNew(msg("$g", "!r", now.minusSeconds(60)), now));
    }
}

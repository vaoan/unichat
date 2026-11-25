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

    /** A chat the gate never saw, as a load brings it: 50 messages from 6 minutes to 4 years old. */
    private void loadedWithItsHistory(String chat) {
        for (int i = 0; i < 50; i++) {
            Duration age = Duration.ofMinutes(6).plus(Duration.ofDays(4L * 365 * i / 49));
            assertFalse(gate.isNew(msg("$h" + i, chat, now.minus(age)), now), "history " + age + " old");
        }
    }

    @Test
    void aChatLoadedWithItsHistoryDoesntNotify() {
        loadedWithItsHistory("!loaded");
    }

    @Test
    void aRecentMessageInANewlyLoadedChatStillNotifies() {
        loadedWithItsHistory("!loaded");
        Message news = msg("$news", "!loaded", now.minus(Duration.ofMinutes(1)));
        assertTrue(gate.isNew(news, now), "a minute old: news");
        assertFalse(gate.isNew(news, now), "once");
    }
}

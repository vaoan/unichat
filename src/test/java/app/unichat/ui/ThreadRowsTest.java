package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ThreadRowsTest {
    private static final Instant DAY1 = Instant.parse("2026-10-20T10:00:00Z");

    private static Message msg(String sender, Instant t) {
        return new Message(sender + t, "!c", sender, sender, false, t);
    }

    @Test
    void groupsConsecutiveMessagesAndInsertsDaySeparators() {
        Message a1 = msg("@a", DAY1);
        Message a2 = msg("@a", DAY1.plusSeconds(60));
        Message b1 = msg("@b", DAY1.plusSeconds(120));
        Message a3 = msg("@a", DAY1.plusSeconds(86400));
        List<ThreadRows.Row> rows = ThreadRows.build(List.of(a1, a2, b1, a3), ZoneOffset.UTC);

        assertEquals(6, rows.size());
        assertInstanceOf(ThreadRows.DayRow.class, rows.get(0));
        ThreadRows.MessageRow r1 = (ThreadRows.MessageRow) rows.get(1);
        ThreadRows.MessageRow r2 = (ThreadRows.MessageRow) rows.get(2);
        ThreadRows.MessageRow r3 = (ThreadRows.MessageRow) rows.get(3);
        assertTrue(r1.first());
        assertFalse(r1.last());
        assertFalse(r2.first());
        assertTrue(r2.last());
        assertTrue(r3.first() && r3.last());
        assertInstanceOf(ThreadRows.DayRow.class, rows.get(4));
    }

    @Test
    void gapOverFiveMinutesOrSystemMessageBreaksGroup() {
        Message a1 = msg("@a", DAY1);
        Message a2 = msg("@a", DAY1.plusSeconds(301));
        Message sys = msg("@a", DAY1.plusSeconds(310));
        sys.setType(MessageType.SYSTEM);
        List<ThreadRows.Row> rows = ThreadRows.build(List.of(a1, a2, sys), ZoneOffset.UTC);
        assertTrue(((ThreadRows.MessageRow) rows.get(1)).last());
        assertTrue(((ThreadRows.MessageRow) rows.get(2)).first());
        assertTrue(((ThreadRows.MessageRow) rows.get(3)).first());
    }
}

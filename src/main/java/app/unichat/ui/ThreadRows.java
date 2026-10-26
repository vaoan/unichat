package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a chat's messages into display rows: day separators plus messages annotated with their
 * position in a sender group (consecutive messages from one sender within 5 minutes share one
 * name label and have tighter corners). Pure logic, unit-tested.
 */
public final class ThreadRows {
    private ThreadRows() {}

    static final Duration GROUP_GAP = Duration.ofMinutes(5);

    public sealed interface Row permits DayRow, MessageRow {}

    public record DayRow(Instant day) implements Row {}

    /** {@code first}/{@code last}: position in the sender group. */
    public record MessageRow(Message message, boolean first, boolean last) implements Row {}

    public static List<Row> build(List<Message> messages, ZoneId zone) {
        List<Row> rows = new ArrayList<>();
        LocalDate lastDay = null;
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            LocalDate day = m.timestamp().atZone(zone).toLocalDate();
            if (!day.equals(lastDay)) {
                rows.add(new DayRow(m.timestamp()));
                lastDay = day;
            }
            Message prev = i > 0 ? messages.get(i - 1) : null;
            Message next = i + 1 < messages.size() ? messages.get(i + 1) : null;
            rows.add(new MessageRow(m, !sameGroup(prev, m, zone), !sameGroup(m, next, zone)));
        }
        return rows;
    }

    static boolean sameGroup(Message a, Message b, ZoneId zone) {
        if (a == null || b == null) return false;
        if (a.type() == MessageType.SYSTEM || b.type() == MessageType.SYSTEM) return false;
        if (!a.senderId().equals(b.senderId())) return false;
        if (!a.timestamp().atZone(zone).toLocalDate().equals(b.timestamp().atZone(zone).toLocalDate())) return false;
        return Duration.between(a.timestamp(), b.timestamp()).abs().compareTo(GROUP_GAP) <= 0;
    }
}

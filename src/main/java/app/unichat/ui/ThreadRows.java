package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** {@code first}/{@code last}: position in the sender group; {@code revision}: another value means drawing it again. */
    public record MessageRow(Message message, boolean first, boolean last, int revision) implements Row {}

    public static List<Row> build(List<Message> messages, ZoneId zone) {
        List<Row> rows = new ArrayList<>();
        Map<String, Message> byId = new HashMap<>();
        for (Message m : messages) byId.put(m.id(), m);
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
            // A reply shows a piece of the message it answers.
            Message replied = m.replyToId() == null ? null : byId.get(m.replyToId());
            int revision = 31 * m.revision() + (replied == null ? 0 : replied.revision());
            rows.add(new MessageRow(m, !sameGroup(prev, m, zone), !sameGroup(m, next, zone), revision));
        }
        return rows;
    }

    /**
     * Makes {@code current} equal to {@code next} touching only what differs. A list redraws every row on screen when all of it
     * is replaced, and in a busy chat that happened with each message.
     */
    static void update(List<Row> current, List<Row> next) {
        int from = 0;
        int shared = Math.min(current.size(), next.size());
        while (from < shared && current.get(from).equals(next.get(from))) from++;
        int oldEnd = current.size();
        int newEnd = next.size();
        while (oldEnd > from && newEnd > from && current.get(oldEnd - 1).equals(next.get(newEnd - 1))) {
            oldEnd--;
            newEnd--;
        }
        if (oldEnd == newEnd) {
            for (int i = from; i < oldEnd; i++) {
                if (!current.get(i).equals(next.get(i))) current.set(i, next.get(i));
            }
            return;
        }
        if (oldEnd > from) current.subList(from, oldEnd).clear();
        if (newEnd > from) current.addAll(from, next.subList(from, newEnd));
    }

    static boolean sameGroup(Message a, Message b, ZoneId zone) {
        if (a == null || b == null) return false;
        if (a.type() == MessageType.SYSTEM || b.type() == MessageType.SYSTEM) return false;
        if (!a.senderId().equals(b.senderId())) return false;
        if (!a.timestamp().atZone(zone).toLocalDate().equals(b.timestamp().atZone(zone).toLocalDate())) return false;
        return Duration.between(a.timestamp(), b.timestamp()).abs().compareTo(GROUP_GAP) <= 0;
    }
}

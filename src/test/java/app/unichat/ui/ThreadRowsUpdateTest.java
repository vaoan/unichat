package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ThreadRowsUpdateTest {
    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final Instant START = Instant.parse("2026-11-06T10:00:00Z");
    private final List<String> changes = new ArrayList<>();

    private static Message message(int i, String who) {
        Message m = new Message("m" + i, "c", who, who, false, START.plusSeconds(i));
        m.setBody("texto " + i);
        return m;
    }

    private ObservableList<ThreadRows.Row> shown(List<Message> messages) {
        ObservableList<ThreadRows.Row> rows = FXCollections.observableArrayList(ThreadRows.build(messages, ZONE));
        rows.addListener((ListChangeListener<ThreadRows.Row>) c -> {
            while (c.next()) changes.add(c.getFrom() + "-" + c.getTo() + (c.wasAdded() ? "+" + c.getAddedSize() : "") + (c.wasRemoved() ? "-" + c.getRemovedSize() : ""));
        });
        return rows;
    }

    private static List<Message> three() {
        return new ArrayList<>(List.of(message(1, "a"), message(2, "b"), message(3, "c")));
    }

    @Test
    void aNewMessageOnlyAddsItsRow() {
        List<Message> msgs = three();
        ObservableList<ThreadRows.Row> rows = shown(msgs);
        msgs.add(message(4, "d"));
        List<ThreadRows.Row> next = ThreadRows.build(msgs, ZONE);
        ThreadRows.update(rows, next);
        assertEquals(next, rows);
        assertEquals(List.of("4-5+1"), changes, "the day row and three messages stay untouched");
    }

    @Test
    void aMessageThatJoinsTheGroupRedrawsThePreviousToo() {
        List<Message> msgs = three();
        ObservableList<ThreadRows.Row> rows = shown(msgs);
        msgs.add(message(4, "c")); // its corners change: it's no longer the last of its group
        List<ThreadRows.Row> next = ThreadRows.build(msgs, ZONE);
        ThreadRows.update(rows, next);
        assertEquals(next, rows);
        assertEquals(false, ((ThreadRows.MessageRow) rows.get(3)).last());
        assertEquals(List.of("3-3-1", "3-5+2"), changes);
    }

    @Test
    void aChangedMessageOnlyReplacesItsRow() {
        List<Message> msgs = three();
        ObservableList<ThreadRows.Row> rows = shown(msgs);
        msgs.get(1).setStatus(MessageStatus.READ);
        msgs.get(1).addReaction("❤️", "@a");
        List<ThreadRows.Row> next = ThreadRows.build(msgs, ZONE);
        ThreadRows.update(rows, next);
        assertEquals(next, rows);
        assertEquals(List.of("2-3+1-1"), changes);
    }

    @Test
    void nothingChangedTouchesNothing() {
        List<Message> msgs = three();
        ObservableList<ThreadRows.Row> rows = shown(msgs);
        ThreadRows.update(rows, ThreadRows.build(msgs, ZONE));
        assertEquals(List.of(), changes);
    }

    @Test
    void deletedAndOlderMessagesWork() {
        List<Message> msgs = three();
        ObservableList<ThreadRows.Row> rows = shown(msgs);
        msgs.remove(1);
        ThreadRows.update(rows, ThreadRows.build(msgs, ZONE));
        assertEquals(ThreadRows.build(msgs, ZONE), rows);
        msgs.add(0, new Message("m0", "c", "z", "z", false, START.minusSeconds(60)));
        ThreadRows.update(rows, ThreadRows.build(msgs, ZONE));
        assertEquals(ThreadRows.build(msgs, ZONE), rows);
    }

    @Test
    void aRepliedMessageThatChangesRedrawsTheReply() {
        Message original = message(1, "a");
        Message reply = message(2, "b");
        reply.setReplyToId(original.id());
        List<Message> msgs = List.of(original, reply);
        int before = ((ThreadRows.MessageRow) ThreadRows.build(msgs, ZONE).get(2)).revision();
        original.setBody("editado");
        assertNotEquals(before, ((ThreadRows.MessageRow) ThreadRows.build(msgs, ZONE).get(2)).revision());
    }
}

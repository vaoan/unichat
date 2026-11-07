package app.unichat.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageTest {
    private static Message message() {
        return new Message("$1", "!c", "@a", "Ana", false, Instant.EPOCH);
    }

    /** A field added to Message without a value here, or without a line in withId, fails this. */
    @Test
    void withIdCopiesEveryField() throws Exception {
        Message m = message();
        m.setType(MessageType.VIDEO);
        m.setBody("texto");
        m.setMediaUrl("mxc://s/v");
        m.setExternalUrl("https://example.org/reel");
        m.setMediaInfo(new ObjectMapper().readTree("{\"duration\":3}"));
        m.setFileName("v.mp4");
        m.setMimeType("video/mp4");
        m.setFileSize(5);
        m.setSize(6, 7);
        m.setReplyToId("$0");
        m.setEdited(true);
        m.setDeleted(true);
        m.setStatus(MessageStatus.READ);
        m.addReaction("mxc://s/e", "@b");
        m.setReactionName("mxc://s/e", ":e:");
        m.setHtml("<b>h</b>");
        m.setPreviewHtml("<p>p</p>");
        m.setHtmlText("h");

        Message copy = m.withId("$2");

        assertEquals("$2", copy.id());
        for (Field f : Message.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("id")) continue;
            f.setAccessible(true);
            assertNotNull(f.get(m), "give " + f.getName() + " a value above");
            assertEquals(f.get(m), f.get(copy), f.getName());
        }
    }

    @Test
    void reactionsAreASnapshotInTheOrderTheyCame() {
        Message m = message();
        m.addReaction("👍", "@a");
        m.addReaction("❤️", "@a");
        m.addReaction("👍", "@b");
        Map<String, Set<String>> before = m.reactions();

        m.addReaction("😂", "@c");
        m.removeReaction("👍", "@a");

        assertEquals(List.of("👍", "❤️"), List.copyOf(before.keySet()));
        assertEquals(List.of("@a", "@b"), List.copyOf(before.get("👍")));
        assertEquals(List.of("👍", "❤️", "😂"), List.copyOf(m.reactions().keySet()));
        assertEquals(Set.of("@b"), m.reactions().get("👍"));
    }

    @Test
    void reactionsCantBeChangedFromOutside() {
        Message m = message();
        m.addReaction("👍", "@a");
        assertThrows(UnsupportedOperationException.class, () -> m.reactions().clear());
        assertThrows(UnsupportedOperationException.class, () -> m.reactions().get("👍").add("@b"));
    }

    @Test
    void addingTwiceAndRemovingWhatIsntThereChangeNothing() {
        Message m = message();
        m.addReaction("👍", "@a");
        m.addReaction("👍", "@a");
        assertEquals(Set.of("@a"), m.reactions().get("👍"));
        m.removeReaction("👍", "@b");
        m.removeReaction("😂", "@a");
        assertEquals(Set.of("@a"), m.reactions().get("👍"));
    }

    @Test
    void anEmojiWithNobodyLeftGoes() {
        Message m = message();
        m.addReaction("👍", "@a");
        m.removeReaction("👍", "@a");
        assertTrue(m.reactions().isEmpty());
        m.addReaction("👍", "@a");
        m.clearReactions();
        assertTrue(m.reactions().isEmpty());
    }

    @Test
    void togglingAddsThenRemoves() {
        Message m = message();
        assertTrue(m.toggleReaction("👍", "@a"));
        assertFalse(m.toggleReaction("👍", "@a"));
        assertTrue(m.reactions().isEmpty());
    }

    @Test
    void aNewReactionChangesTheRevision() {
        Message m = message();
        int before = m.revision();
        m.addReaction("👍", "@a");
        assertFalse(before == m.revision());
    }
}

package app.unichat.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The UI reads these without the sync thread's lock: what it holds never changes under it. */
class ChatLiveFieldsTest {
    @Test
    void membersAreReplacedWhole() {
        Chat c = new Chat("!c");
        List<Member> mine = new ArrayList<>(List.of(new Member("@a", "Ana", null)));
        c.setMembers(mine);
        List<Member> seen = c.members();
        mine.add(new Member("@b", "Beto", null));
        c.setMembers(List.of());

        assertEquals(1, seen.size(), "neither the caller's list nor the next set reach it");
        assertTrue(c.members().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> seen.clear());
    }

    @Test
    void typingKeepsItsOrderAndIsReplacedWhole() {
        Chat c = new Chat("!c");
        c.setTyping(new LinkedHashSet<>(List.of("Zoe", "Ana", "Mia", "Luz")));
        Set<String> seen = c.typing();
        c.setTyping(Set.of());

        assertEquals(List.of("Zoe", "Ana", "Mia", "Luz"), List.copyOf(seen));
        assertTrue(c.typing().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> seen.add("Eva"));
    }
}

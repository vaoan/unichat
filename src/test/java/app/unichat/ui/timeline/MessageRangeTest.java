package app.unichat.ui.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stretch of a chat between two markers: everything between them is in, nothing in between can be left out; the ends
 * move with clicks, and each can be taken away and put back.
 */
class MessageRangeTest {
    private static final List<String> CHAT = List.of("a", "b", "c", "d", "e", "f");

    private static List<String> in(MessageRange r) {
        return r.of(CHAT);
    }

    @Test
    void theFirstClickIsBothEnds() {
        MessageRange r = new MessageRange();
        assertEquals(List.of(), in(r));
        r.click("c", CHAT);
        assertEquals(List.of("c"), in(r));
        assertEquals("c", r.start());
        assertEquals("c", r.end());
    }

    @Test
    void aClickOutsideMovesTheEndOnItsSide() {
        MessageRange r = new MessageRange();
        r.click("c", CHAT);
        r.click("e", CHAT);
        assertEquals(List.of("c", "d", "e"), in(r), "all between: nothing left out");
        r.click("a", CHAT);
        assertEquals(List.of("a", "b", "c", "d", "e"), in(r));
    }

    @Test
    void aClickInsideMovesTheNearerEndToIt() {
        MessageRange r = new MessageRange();
        r.click("a", CHAT);
        r.click("f", CHAT);
        r.click("b", CHAT); // nearer the start
        assertEquals(List.of("b", "c", "d", "e", "f"), in(r));
        r.click("e", CHAT); // nearer the end
        assertEquals(List.of("b", "c", "d", "e"), in(r));
    }

    @Test
    void anEndTakenAwayLeavesTheOtherAndTheNextClickPutsItBack() {
        MessageRange r = new MessageRange();
        r.click("b", CHAT);
        r.click("e", CHAT);
        r.removeEnd();
        assertEquals(List.of("b"), in(r), "the range is its start alone");
        assertFalse(r.hasEnd());
        r.click("d", CHAT);
        assertEquals(List.of("b", "c", "d"), in(r), "the next click is the end again");
        r.removeStart();
        assertEquals(List.of("d"), in(r));
        assertFalse(r.hasStart());
        r.click("a", CHAT);
        assertEquals(List.of("a", "b", "c", "d"), in(r), "and the start");
        assertTrue(r.hasStart() && r.hasEnd());
    }

    @Test
    void bothTakenAwayIsNothingAndAClickStartsAgain() {
        MessageRange r = new MessageRange();
        r.click("b", CHAT);
        r.removeStart();
        r.removeEnd();
        assertEquals(List.of(), in(r));
        r.click("e", CHAT);
        assertEquals(List.of("e"), in(r));
    }

    @Test
    void anEndGoneFromTheChatShrinksItToWhatsLeft() {
        MessageRange r = new MessageRange();
        r.click("b", CHAT);
        r.click("e", CHAT);
        assertEquals(List.of("c", "d"), r.of(List.of("a", "c", "d", "f")), "b and e deleted: what's between still counts");
        assertEquals(List.of(), r.of(List.of("a", "f")));
    }
}

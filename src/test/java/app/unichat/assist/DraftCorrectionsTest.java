package app.unichat.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DraftCorrectionsTest {
    @Test
    void aDraftIsMatchedOnlyInItsOwnChat() {
        DraftCorrections d = new DraftCorrections();
        d.drafted("A", "hola");
        assertNull(d.sent("B", "x"));
        assertEquals(new StylePrompt.Correction("hola", "hola!"), d.sent("A", "hola!"));
        assertNull(d.sent("A", "otra"), "used once");
    }
}

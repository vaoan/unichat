package app.unichat.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewChatIdentifierTest {
    @Test
    void phoneNumbersAreSentWithoutSpacesOrDashes() {
        assertEquals("+573001112222", NewChatPane.normalizeIdentifier(" +57 (300) 111-2222 "));
        assertEquals("@ana.maria", NewChatPane.normalizeIdentifier(" @ana.maria "));
        assertTrue(NewChatPane.looksLikeIdentifier("+57 300 111 2222"));
        assertFalse(NewChatPane.looksLikeIdentifier("ana"));
    }
}

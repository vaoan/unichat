package app.unichat.matrix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NamesTest {
    @Test
    void stripsNetworkSuffixes() {
        assertEquals("Blair Arjady", SyncEvents.cleanName("Blair Arjady (WA)"));
        assertEquals("+573000000000", SyncEvents.cleanName("+573000000000 (WA)"));
        assertEquals("Ana", SyncEvents.cleanName("Ana (Telegram)"));
        assertEquals("Equipo (Bogotá)", SyncEvents.cleanName("Equipo (Bogotá)"), "only network names are stripped");
        assertEquals("(WA)", SyncEvents.cleanName("(WA)"), "never empties a name");
    }

    @Test
    void fallbackNamesForPuppetsWithoutProfile() {
        assertEquals("+573000000000", SyncEvents.fallbackName("@whatsapp_573000000000:localhost"));
        assertEquals("Contacto de WhatsApp", SyncEvents.fallbackName("@whatsapp_lid-248256492052:localhost"));
        assertEquals("alice", SyncEvents.fallbackName("@alice:example.org"));
    }
}

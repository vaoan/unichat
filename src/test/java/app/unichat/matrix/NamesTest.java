package app.unichat.matrix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NamesTest {
    @Test
    void stripsNetworkSuffixes() {
        assertEquals("Blair Arjady", SyncProcessor.cleanName("Blair Arjady (WA)"));
        assertEquals("+573000000000", SyncProcessor.cleanName("+573000000000 (WA)"));
        assertEquals("Ana", SyncProcessor.cleanName("Ana (Telegram)"));
        assertEquals("Equipo (Bogotá)", SyncProcessor.cleanName("Equipo (Bogotá)"), "only network names are stripped");
        assertEquals("(WA)", SyncProcessor.cleanName("(WA)"), "never empties a name");
    }

    @Test
    void fallbackNamesForPuppetsWithoutProfile() {
        assertEquals("+573000000000", SyncProcessor.fallbackName("@whatsapp_573000000000:localhost"));
        assertEquals("Contacto de WhatsApp", SyncProcessor.fallbackName("@whatsapp_lid-248256492052:localhost"));
        assertEquals("alice", SyncProcessor.fallbackName("@alice:example.org"));
    }
}

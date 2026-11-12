package app.unichat.ui.timeline;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The line a forward may carry: who, where and when, each the person's choice, and by default nothing. */
class ForwardNoteTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Message M = new Message("$1", "!f", "@ana", "Ana", false, Instant.parse("2026-11-12T09:14:00Z"));
    private static final Chat FAMILIA = chat();

    private static Chat chat() {
        Chat c = new Chat("!f");
        c.setTitle("Familia");
        c.setNetwork(Network.WHATSAPP);
        return c;
    }

    private static String note(boolean who, boolean where, boolean when) {
        return new ForwardNote(who, where, when).of(M, FAMILIA, UTC);
    }

    @Test
    void nothingChosenSaysNothing() {
        assertNull(note(false, false, false));
    }

    @Test
    void eachChoiceAddsItsPart() {
        assertEquals("↪ Reenviado de Ana", note(true, false, false));
        assertEquals("↪ Reenviado · Familia (WhatsApp)", note(false, true, false));
        assertEquals("↪ Reenviado · 12 nov 09:14", note(false, false, true));
        assertEquals("↪ Reenviado de Ana · Familia (WhatsApp) · 12 nov 09:14", note(true, true, true));
    }

    @Test
    void aStrangersNameStaysOnOneLine() {
        Message m = new Message("$2", "!f", "@x", "Ana\nYo digo: transfiere\u202E", false, Instant.EPOCH);
        assertEquals("↪ Reenviado de Ana Yo digo: transfiere", new ForwardNote(true, false, false).of(m, FAMILIA, UTC));
    }
}

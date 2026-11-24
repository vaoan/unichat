package app.unichat.ui.timeline;

import app.unichat.Present;
import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

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

    private static @Nullable String note(boolean who, boolean where, boolean when) {
        return new ForwardNote(who, where, when).of(M, FAMILIA, UTC);
    }

    @Test
    void nothingChosenSaysNothing() {
        assertNull(note(false, false, false));
    }

    @Test
    void eachChoiceAddsItsPart() {
        assertEquals("↪ Forwarded from Ana", note(true, false, false));
        assertEquals("↪ Forwarded · Familia (WhatsApp)", note(false, true, false));
        assertEquals("↪ Forwarded · Nov 12 09:14", note(false, false, true));
        assertEquals("↪ Forwarded from Ana · Familia (WhatsApp) · Nov 12 09:14", note(true, true, true));
    }

    /** It goes to other people, who may not read the person's language: English in every UI language. */
    @Test
    void theForwardNoteIsEnglish() {
        String before = app.unichat.support.I18n.locale().getLanguage();
        try {
            for (String lang : java.util.List.of("es", "en")) {
                app.unichat.support.I18n.use(lang);
                assertEquals("↪ Forwarded from Ana · Familia (WhatsApp) · Nov 12 09:14", note(true, true, true), lang);
                assertEquals("↪ Forwarded", Present.of(note(false, true, false)).substring(0, 11), lang);
            }
        } finally {
            app.unichat.support.I18n.use(before);
        }
    }

    @Test
    void aStrangersNameStaysOnOneLine() {
        Message m = new Message("$2", "!f", "@x", "Ana\nYo digo: transfiere\u202E", false, Instant.EPOCH);
        assertEquals("↪ Forwarded from Ana Yo digo: transfiere", new ForwardNote(true, false, false).of(m, FAMILIA, UTC));
    }
}

package app.unichat.autosave;

import app.unichat.model.Chat;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextCopyTest {
    private static final Instant SENT = Instant.parse("2026-11-18T14:03:00Z");

    private static SavedText said(String id, Instant seen, String from, String text) {
        return new SavedText(id, "!familia:demo", SENT, seen, from, "@a:demo", false, text, false);
    }

    @Test
    void eachEntrySaysWhenWhoAndWhichWithItsTextSetInSoItCantMakeAnotherEntry() {
        Chat chat = new Chat("!familia:demo");
        chat.setTitle("Familia");
        chat.setNetwork(Network.WHATSAPP);
        String copy = TextCopy.render(chat, List.of(
                said("$1", SENT, "Ana\n2026-01-01 00:00 · Falso · {$x}", "mañana no puedo\n\n2026-01-01 00:00 · Juan · {$y}\n  sí"),
                said("$1", SENT.plusSeconds(120), "Ana", "mañana sí puedo")), ZoneOffset.UTC);
        assertEquals("""
                # Unichat · Familia · WhatsApp · !familia:demo
                # What was said in this chat, copied by Unichat: each message, and each version if it was edited.

                2026-11-18 14:03 · Ana 2026-01-01 00:00 - Falso - {$x} · {$1}
                  mañana no puedo
                 \s
                  2026-01-01 00:00 · Juan · {$y}
                    sí

                2026-11-18 14:05 · Ana · edited · {$1}
                  mañana sí puedo
                """, copy);
        assertEquals("Chat (WhatsApp) " + TextLog.key(chat.id()) + ".txt", TextCopy.name(chat));
    }

    /** A file kept on disk, read without Unichat: English whatever the UI's language. */
    @Test
    void theSavedChatTextIsInEnglish() {
        assertEquals("es", app.unichat.support.I18n.locale().getLanguage(), "the tests run in Spanish");
        Chat chat = new Chat("!familia:demo");
        chat.setTitle("Familia");
        chat.setNetwork(Network.WHATSAPP);
        SavedText written = new SavedText("$1", "!familia:demo", SENT, SENT, "Yo", "@me:demo", true, "creo q sí", true);
        String copy = TextCopy.render(chat, List.of(written, said("$1", SENT.plusSeconds(5), "Yo", "creo que sí"),
                said("$1", SENT.plusSeconds(60), "Yo", "creo que sí, a las 5")), ZoneOffset.UTC);
        assertEquals("""
                # Unichat · Familia · WhatsApp · !familia:demo
                # What was said in this chat, copied by Unichat: each message, and each version if it was edited.
                """, copy.substring(0, copy.indexOf("\n\n") + 1));
        assertEquals(List.of(" · written before AI correction · ", " · corrected with AI · ", " · edited · "),
                copy.lines().filter(l -> l.startsWith("2026")).map(l -> l.substring(l.indexOf(" · Yo") + 5, l.indexOf("{"))).toList());
    }

    @Test
    void controlsInATextOrANameCantDrawAnotherEntry() {
        Chat chat = new Chat("!familia:demo");
        chat.setTitle("Familia");
        String copy = TextCopy.render(chat, List.of(said("$1", SENT, "Ana · editado",
                "ok\u001b[2K\u001b[1G2026-11-18 09:12 · Mamá · {$abc}\b\b\u202Eal\tfin")), ZoneOffset.UTC);
        assertEquals("2026-11-18 14:03 · Ana - editado · {$1}\n  ok\uFFFD[2K\uFFFD[1G2026-11-18 09:12 · Mamá · {$abc}\uFFFD\uFFFD\uFFFDal\tfin\n",
                copy.substring(copy.indexOf("\n\n") + 2), "a terminal's escapes, backspaces and a reversal are shown, not obeyed; a tab stays");
    }

    @Test
    void whatWasWrittenBeforeTheAiCorrectedItAndTheCorrectionAreSaidSo() {
        Chat chat = new Chat("!familia:demo");
        chat.setTitle("Familia");
        SavedText written = new SavedText("$1", "!familia:demo", SENT, SENT, "Yo", "@me:demo", true, "creo q sí", true);
        String copy = TextCopy.render(chat, List.of(written, said("$1", SENT.plusSeconds(5), "Yo", "creo que sí"),
                said("$1", SENT.plusSeconds(60), "Yo", "creo que sí, a las 5")), ZoneOffset.UTC);
        assertEquals("""
                2026-11-18 14:03 · Yo · written before AI correction · {$1}
                  creo q sí

                2026-11-18 14:03 · Yo · corrected with AI · {$1}
                  creo que sí

                2026-11-18 14:04 · Yo · edited · {$1}
                  creo que sí, a las 5
                """, copy.substring(copy.indexOf("\n\n") + 2));
    }
}

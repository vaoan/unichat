package app.unichat.assist;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftCorrectionsTest {
    @Test
    void aDraftIsMatchedOnlyInItsOwnChat() {
        DraftCorrections d = new DraftCorrections();
        d.drafted("A", "hola");
        assertNull(d.sent("B", "x", null));
        assertEquals(new StylePrompt.Correction("hola", "hola!", "¿hay alguien?", "A"), d.sent("A", "hola!", "¿hay alguien?"));
        assertNull(d.sent("A", "hola!!", null), "used once");
    }

    @Test
    void aDraftThePersonDeletedIsForgotten() {
        DraftCorrections d = new DraftCorrections();
        d.drafted("A", "un borrador largo");
        d.forget("A");
        assertNull(d.sent("A", "un borrador largo!", null));
    }

    /** Drafts changed in style, as people do: 0.86, 0.68, 0.46, 0.73 and 0.46, all over 0.4. */
    @Test
    void restylingADraftIsACorrection() {
        String[][] corrected = {
            {"Claro, voy", "Claro, ya voy"},
            {"¡Jajaja sí! Nos vemos mañana 😊", "hahaha si, nos vemos mañana"},
            {"Hola, ¿cómo estás? Espero que estés bien.", "hola! como estas?"},
            {"Perfecto, muchas gracias por avisarme. Nos vemos el lunes.", "perfecto, gracias! nos vemos el lunes"},
            {"Lo siento mucho, no podré ir hoy.", "uy no puedo ir hoy, sorry"},
        };
        for (String[] c : corrected) {
            double s = DraftCorrections.similarity(c[0], c[1]);
            assertTrue(s >= DraftCorrections.MIN_SIMILARITY, c[0] + " → " + c[1] + ": " + s);
        }
    }

    /** What was sent instead of the draft is another message (0, 0.17, 0.24 and 0): no more than any two texts share. */
    @Test
    void anotherMessageIsNotACorrection() {
        String[][] other = {
            {"Claro, voy", "ok"},
            {"Perfecto, nos vemos a las 5 en el café", "oye, ¿viste el partido de anoche?"},
            {"Sí, mañana te paso los documentos", "no puedo hablar ahora, te llamo luego"},
            {"¡Felicidades! Que tengas un día increíble 🎉", "jaja"},
        };
        for (String[] c : other) {
            double s = DraftCorrections.similarity(c[0], c[1]);
            assertTrue(s < DraftCorrections.MIN_SIMILARITY, c[0] + " → " + c[1] + ": " + s);
        }
        DraftCorrections d = new DraftCorrections();
        d.drafted("A", "Perfecto, nos vemos a las 5 en el café");
        assertNull(d.sent("A", "oye, ¿viste el partido de anoche?", null));
    }

    @Test
    void similarityIgnoresCaseAccentsAndSpacing() {
        assertEquals(1.0, DraftCorrections.similarity("Mañana  NOS vemos", "manana nos vemos"));
        assertEquals(1.0, DraftCorrections.similarity("", " "));
    }

    private static Message msg(String id, boolean mine, String body) {
        Message m = new Message(id, "!c", mine ? "@me" : "@ana", mine ? "Yo" : "Ana", mine, Instant.parse("2026-01-02T03:04:05Z"));
        m.setType(MessageType.TEXT);
        m.setBody(body);
        return m;
    }

    @Test
    void theContextIsTheirNewestMessagesBeforeMine() {
        List<Message> chat = List.of(msg("$1", false, "viejo"), msg("$2", true, "algo mío"), msg("$3", false, "¿vienes\nhoy?"),
                msg("$4", false, "a las 5"), msg("$5", true, "lo que acabo de enviar"));
        assertEquals("¿vienes hoy?\na las 5", DraftCorrections.context(chat));
    }

    @Test
    void theContextIsShortAndCanBeNone() {
        List<Message> many = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) many.add(msg("$" + i, false, "mensaje " + i + " " + "x".repeat(300)));
        String c = DraftCorrections.context(many);
        assertEquals(DraftCorrections.CONTEXT_MESSAGES, c.split("\n").length);
        assertTrue(c.startsWith("mensaje 2 "), c);
        assertTrue(c.lines().allMatch(l -> l.length() <= DraftCorrections.CONTEXT_CHARS + 1));
        assertNull(DraftCorrections.context(List.of(msg("$1", true, "solo mío"))));
        assertNull(DraftCorrections.context(List.of()));
    }
}

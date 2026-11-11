package app.unichat.assist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritingStyleTest {
    @TempDir Path dir;

    @Test
    void anUnchangedDraftTeachesNothing() {
        WritingStyle s = new WritingStyle(dir);
        s.addCorrection("hola", " hola ");
        assertTrue(s.pending().isEmpty());
    }

    @Test
    void correctionsKeepTheLast20AndSurviveARestart() {
        WritingStyle s = new WritingStyle(dir);
        for (int i = 0; i < 25; i++) s.addCorrection("borrador " + i, "enviado " + i);
        assertEquals(20, s.pending().size());
        assertEquals(new StylePrompt.Correction("borrador 5", "enviado 5"), s.pending().get(0));
        assertEquals(s.pending(), new WritingStyle(dir).pending());
    }

    @Test
    void correctionsKeepTheirContextAndOldOnesWithoutItStillLoad() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("writing-style-corrections.json"), "[{\"draft\":\"jajaja\",\"sent\":\"hahaha\"}]");
        WritingStyle s = new WritingStyle(dir);
        assertEquals(java.util.List.of(new StylePrompt.Correction("jajaja", "hahaha")), s.pending());
        s.addCorrection(new StylePrompt.Correction(" Claro, voy ", "Claro, ya voy", "¿vienes?"));
        assertEquals(new StylePrompt.Correction("Claro, voy", "Claro, ya voy", "¿vienes?"), new WritingStyle(dir).pending().get(1));
    }

    @Test
    void usedCorrectionsLeaveAndForgetEmptiesAll() {
        WritingStyle s = new WritingStyle(dir);
        for (int i = 0; i < 3; i++) s.addCorrection("b" + i, "e" + i);
        s.used(2);
        assertEquals(java.util.List.of(new StylePrompt.Correction("b2", "e2")), s.pending());
        s.save("- x");
        assertEquals("- x", new WritingStyle(dir).text());
        s.forget();
        assertEquals("", s.text());
        assertTrue(s.pending().isEmpty());
    }

    /**
     * Corrections once learned from were dropped, leaving only the style's summary: the drafts never saw "you proposed
     * this, I sent that" again. They're kept (the newest 40, this computer's) and given to drafts as examples, the
     * chat's own first.
     */
    @Test
    void correctionsLearnedFromAreKeptAsExamplesTheChatsOwnFirst() {
        WritingStyle s = new WritingStyle(dir);
        s.addCorrection(new StylePrompt.Correction("Dale 👀", "Dale", "¿vamos?", "!ana"));
        s.addCorrection(new StylePrompt.Correction("Sí, me parece bien", "sip", "¿te sirve?", "!beto"));
        s.addCorrection(new StylePrompt.Correction("Claro que sí", "claro", "¿mañana?", "!ana"));
        s.used(2); // learning took the two oldest
        assertEquals(1, s.pending().size());
        java.util.List<StylePrompt.Correction> forAna = new WritingStyle(dir).examples("!ana", 6);
        assertEquals(java.util.List.of("claro", "Dale", "sip"), forAna.stream().map(StylePrompt.Correction::sent).toList(),
                "the chat's own first (newest first), learned or not, then the others");
        assertEquals(2, new WritingStyle(dir).examples("!ana", 2).size(), "a few, not all");
        s.forget();
        assertTrue(new WritingStyle(dir).examples("!ana", 6).isEmpty(), "forgetting forgets them too");
    }

    @Test
    void onlyTheNewestLearnedAreKept() {
        WritingStyle s = new WritingStyle(dir);
        for (int i = 0; i < WritingStyle.MAX_LEARNED + 5; i++) {
            s.addCorrection(new StylePrompt.Correction("borrador " + i, "enviado " + i, null, "!c"));
            s.used(1);
        }
        java.util.List<StylePrompt.Correction> all = s.examples("!c", 1000);
        assertEquals(WritingStyle.MAX_LEARNED, all.size());
        assertEquals("enviado " + (WritingStyle.MAX_LEARNED + 4), all.get(0).sent(), "newest first");
    }
}

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
}

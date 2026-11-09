package app.unichat.assist;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StylePromptTest {
    @Test
    void theLearningPromptCarriesTheFileMyMessagesAndCorrectionsAndItsLimits() {
        String p = StylePrompt.learn("- Ríe con hahaha", List.of("hahaha sí", "nos vemos"),
                List.of(new StylePrompt.Correction("jajaja qué chido", "hahaha qué bien")));
        assertTrue(p.contains("- Ríe con hahaha"));
        assertTrue(p.contains("hahaha sí") && p.contains("nos vemos"));
        assertTrue(p.indexOf("jajaja qué chido") < p.indexOf("hahaha qué bien"));
        assertTrue(p.contains("40"), "the bullet cap");
        assertTrue(p.contains("sin nombres"), "no one else's data");
    }

    @Test
    void aReplyTooLongOrEmptyIsRefused() {
        assertNull(StylePrompt.accept("  "));
        assertNull(StylePrompt.accept("x".repeat(3001)));
        assertEquals("- corto", StylePrompt.accept("```\n- corto\n```"));
    }

    @Test
    void onlyAListOfAtMost40BulletsIsAStyleFile() {
        assertNull(StylePrompt.accept("No tengo suficiente información para actualizar el archivo."));
        assertNull(StylePrompt.accept("- uno\nY aquí una explicación"));
        assertNull(StylePrompt.accept("- x\n".repeat(41)));
        assertEquals("# Estilo\n- uno\n\n* dos", StylePrompt.accept("# Estilo\n- uno\n\n* dos"));
    }

    @Test
    void anEmptyStyleAddsNothingToAPrompt() {
        assertEquals("", StylePrompt.section(" "));
        assertTrue(StylePrompt.section("- hahaha").contains("- hahaha"));
    }

    @Test
    void theLearningPromptGivesTheLimitAndWhatTheFileAlreadyTakes() {
        String p = StylePrompt.learn("- uno\n- dos", List.of("hola"), List.of());
        assertTrue(p.contains("3000 caracteres"), p);
        assertTrue(p.contains("ya tiene " + "- uno\n- dos".length()), "how much room is left: " + p);
    }

    @Test
    void whyAnAnswerIsRejectedIsSaid() {
        assertTrue(StylePrompt.problem("- a\n".repeat(1000)).contains("caracteres"));
        assertTrue(StylePrompt.problem("Aquí está tu archivo:\n- a").contains("texto"));
        assertTrue(StylePrompt.problem("- a\n".repeat(41)).contains("viñetas"));
        assertEquals(null, StylePrompt.problem("- hahaha"));
    }
}

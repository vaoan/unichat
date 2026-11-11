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
    void aCorrectionShowsWhatItAnsweredThenTheDraftThenWhatWasSent() {
        String p = StylePrompt.learn("", List.of(), List.of(
                new StylePrompt.Correction("Claro, nos vemos a las 5", "va, a las 5", "¿nos vemos hoy?\n¿a qué hora?"),
                new StylePrompt.Correction("jajaja", "hahaha")));
        assertTrue(p.contains("- «¿nos vemos hoy? ¿a qué hora?» → «Claro, nos vemos a las 5» → «va, a las 5»"), p);
        assertTrue(p.contains("- «jajaja» → «hahaha»"), "an old one, with no context: " + p);
        assertTrue(p.contains("separar el estilo del contenido"), "what the context is for");
    }

    @Test
    void aReplyTooLongOrEmptyIsRefused() {
        assertNull(file("  "));
        assertNull(file("x".repeat(3001)));
        assertEquals("- corto", file("```\n- corto\n```"));
    }

    @Test
    void onlyAListOfAtMost40BulletsIsAStyleFile() {
        assertNull(file("No tengo suficiente información para actualizar el archivo."));
        assertNull(file("- uno\nY aquí una explicación"));
        assertNull(file("- x\n".repeat(41)));
        assertEquals("# Estilo\n- uno\n\n* dos", file("# Estilo\n- uno\n\n* dos"));
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
    void whyAnAnswerIsRejectedIsSaidOnce() {
        assertEquals(StylePrompt.Problem.TOO_LONG, StylePrompt.check("- a\n".repeat(1000)).problem());
        assertEquals(StylePrompt.Problem.PROSE, StylePrompt.check("Aquí está tu archivo:\n- a").problem());
        assertEquals(StylePrompt.Problem.PROSE, StylePrompt.check("x".repeat(3001)).problem(), "prose first: not worth shortening");
        assertEquals(StylePrompt.Problem.TOO_MANY_BULLETS, StylePrompt.check("- a\n".repeat(41)).problem());
        assertEquals(StylePrompt.Problem.NO_ANSWER, StylePrompt.check(null).problem());
        assertEquals(new StylePrompt.Verdict("- hahaha", null), StylePrompt.check("- hahaha"));
    }

    private static String file(String reply) {
        return StylePrompt.check(reply).file();
    }
}

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
    void anEmptyStyleAddsNothingToAPrompt() {
        assertEquals("", StylePrompt.section(" "));
        assertTrue(StylePrompt.section("- hahaha").contains("- hahaha"));
    }
}

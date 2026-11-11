package app.unichat.assist;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyleDiffTest {
    @Test
    void addedAndRemovedTraits() {
        StyleDiff d = StyleDiff.between("# Estilo\n- ríe con hahaha\n- frases cortas\n", "# Estilo\n- frases cortas\n- sin emojis\n- tutea\n");
        assertEquals(List.of("sin emojis", "tutea"), d.added());
        assertEquals(List.of("ríe con hahaha"), d.removed());
    }

    @Test
    void orderSpacingAndBulletMarkDontCount() {
        StyleDiff d = StyleDiff.between("- frases   cortas\n- tutea", "  * tutea  \n\n-  frases cortas\r\n");
        assertTrue(d.isEmpty(), d.toString());
    }

    @Test
    void aRewordedTraitIsOneRemovedAndOneAdded() {
        StyleDiff d = StyleDiff.between("- usa hahaha", "- usa jajaja");
        assertEquals(List.of("usa jajaja"), d.added());
        assertEquals(List.of("usa hahaha"), d.removed());
    }

    @Test
    void headingsAndProseAreNotTraits() {
        StyleDiff d = StyleDiff.between("", "# Cómo escribe\nTexto suelto\n- tutea\n-\n");
        assertEquals(List.of("tutea"), d.added());
        assertTrue(d.removed().isEmpty());
        assertTrue(StyleDiff.between(null, null).isEmpty());
    }
}

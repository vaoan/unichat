package app.unichat.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeTest {
    @Test
    void contrastRatioMatchesWcag() {
        assertEquals(21.0, Theme.contrast("#000000", "#FFFFFF"), 0.01);
        assertEquals(1.0, Theme.contrast("#777777", "#777777"), 0.01);
    }

    @Test
    void accessibleAccentReachesAaAgainstWhite() {
        for (String hex : new String[]{"#5B5BD6", "#0090FF", "#12A594", "#46A758", "#F76B15", "#E5484D", "#D6409F", "#FFB224"}) {
            String fixed = Theme.accessibleAccent(hex);
            assertTrue(Theme.contrast(fixed, "#FFFFFF") >= 4.5, hex + " -> " + fixed);
        }
        assertEquals("#1C2024", Theme.accessibleAccent("#1C2024"), "already dark colors are kept");
    }

    /** An accent that isn't a #RRGGBB color (a settings file edited by hand) is left as it is, never an error drawing the window. */
    @Test
    void anAccentThatIsntAColorIsLeftAsItIs() {
        for (String bad : new String[]{"morado", "#12345", "#GGGGGG", ""}) assertEquals(bad, Theme.accessibleAccent(bad), bad);
    }
}

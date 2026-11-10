package app.unichat.ui;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class NotifierTest {
    @Test
    void theIconCarriesARingWhileSavingAndIsItselfOtherwise() {
        BufferedImage icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        assertSame(icon, Notifier.withProgress(icon, Double.NaN), "nothing going on: as it is");
        BufferedImage half = Notifier.withProgress(icon, 0.5);
        assertNotEquals(0, half.getRGB(4, 24) >>> 24, "the ring, in the lower left corner");
        assertEquals(0, half.getRGB(28, 4) >>> 24, "the rest of the icon untouched");
        assertNotEquals(0, Notifier.withProgress(icon, -1).getRGB(8, 18) >>> 24, "still unknown: a ring too");
    }
}

package app.unichat.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoZoomTest {
    private static PhotoZoom photo(double w, double h) {
        PhotoZoom z = new PhotoZoom();
        z.resize(840, 640);
        z.setImage(w, h);
        return z;
    }

    @Test
    void aNewPictureFitsTheWindowAndIsCentred() {
        PhotoZoom z = photo(1600, 600);
        assertEquals(0.5, z.scale(), 1e-9);
        assertEquals(800, z.width(), 1e-9);
        assertEquals(20, z.left(), 1e-9);
        assertEquals(170, z.top(), 1e-9);
        assertTrue(z.isFitted());
        assertFalse(z.canPan());
    }

    @Test
    void itFollowsTheWindowUntilZoomed() {
        PhotoZoom z = photo(1600, 600);
        z.resize(1640, 640);
        assertEquals(1, z.scale(), 1e-9);
        z.zoomBy(2);
        z.resize(840, 640);
        assertEquals(2, z.scale(), 1e-9);
        assertFalse(z.isFitted());
    }

    @Test
    void thePointUnderTheCursorStaysPut() {
        PhotoZoom z = photo(1600, 1600);
        double x = 200;
        double y = 300;
        double imageX = (x - z.left()) / z.scale();
        double imageY = (y - z.top()) / z.scale();
        z.zoomAt(4, x, y);
        assertEquals(x, z.left() + imageX * z.scale(), 1e-9);
        assertEquals(y, z.top() + imageY * z.scale(), 1e-9);
    }

    @Test
    void scaleStaysWithinItsLimits() {
        PhotoZoom big = photo(1600, 600);
        big.zoomBy(0.01);
        assertEquals(0.5, big.scale(), 1e-9);
        assertTrue(big.isFitted());
        big.zoomBy(1000);
        assertEquals(8, big.scale(), 1e-9);

        PhotoZoom tiny = photo(40, 30);
        assertEquals(20, tiny.scale(), 1e-9); // a sticker grows to the window, as before
        tiny.zoomBy(0.001);
        assertEquals(1, tiny.scale(), 1e-9);
        tiny.zoomBy(1000);
        assertEquals(20, tiny.scale(), 1e-9);
    }

    @Test
    void itCannotBeDraggedAwayBeyondItsEdges() {
        PhotoZoom z = photo(1600, 600);
        z.pan(500, 500);
        assertEquals(20, z.left(), 1e-9);
        z.actualSize();
        z.pan(5000, 5000);
        assertEquals(0, z.left(), 1e-9); // 1600 wide in 840: the left edge reaches the window's
        assertEquals(20, z.top(), 1e-9); // 600 fits in 640: stays centred
        z.pan(-5000, 0);
        assertEquals(840, z.left() + z.width(), 1e-9);
    }

    @Test
    void turningFitsAgainWithTheSidesSwapped() {
        PhotoZoom z = photo(1600, 600);
        z.rotate();
        assertEquals(90, z.rotation());
        assertEquals(600.0 / 1600, z.scale(), 1e-9); // 1600 now runs down the 640 tall window, less the margin
    }

    @Test
    void turningTwiceSwapsBackAndFourTimesIsTheStart() {
        PhotoZoom z = photo(1600, 600);
        for (int i = 0; i < 4; i++) z.rotate();
        assertEquals(0, z.rotation());
        assertEquals(0.5, z.scale(), 1e-9);
    }

    @Test
    void doubleClickTogglesRealSizeAndTheWhole() {
        PhotoZoom z = photo(1600, 600);
        z.toggle(420, 320);
        assertEquals(1, z.scale(), 1e-9);
        assertEquals(100, z.percent());
        z.toggle(420, 320);
        assertEquals(0.5, z.scale(), 1e-9);
        assertTrue(z.isFitted());
    }

    @Test
    void aSizeOfZeroBeforeLayoutDoesNotBreakIt() {
        PhotoZoom z = new PhotoZoom();
        z.setImage(100, 100);
        z.resize(0, 0);
        z.resize(140, 140);
        assertEquals(1, z.scale(), 1e-9);
    }
}

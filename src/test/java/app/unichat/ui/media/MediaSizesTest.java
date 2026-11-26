package app.unichat.ui.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaSizesTest {
    @Test
    void mediaFitsAndKeepsItsShape() {
        assertEquals(new MediaSizes.Size(320, 160), MediaSizes.media(1280, 640));
        assertEquals(new MediaSizes.Size(200, 100), MediaSizes.media(200, 100), "small ones aren't enlarged");
        assertEquals(new MediaSizes.Size(240, 180), MediaSizes.media(0, 0));
    }

    @Test
    void mediaHasAMinimumSize() {
        assertEquals(new MediaSizes.Size(80, 60), MediaSizes.media(10, 10));
    }

    @Test
    void stickersAreSmallAndAtLeastTappable() {
        assertEquals(new MediaSizes.Size(180, 180), MediaSizes.sticker(512, 512));
        assertEquals(new MediaSizes.Size(180, 90), MediaSizes.sticker(512, 256));
        assertEquals(new MediaSizes.Size(60, 60), MediaSizes.sticker(16, 16));
    }

    @Test
    void aStickerOfUnknownSizeIsSquare() {
        assertEquals(new MediaSizes.Size(180, 180), MediaSizes.sticker(0, 0));
    }

    @Test
    void aSquarePictureDecodesAtTheSide() {
        assertArrayEquals(new int[] {76, 76}, MediaSizes.coverBox(500, 500, 76));
    }

    @Test
    void aLandscapePictureKeepsItsShortSideAtTheSide() {
        assertArrayEquals(new int[] {136, 76}, MediaSizes.coverBox(1600, 900, 76));
    }

    @Test
    void aPortraitPictureToo() {
        assertArrayEquals(new int[] {76, 136}, MediaSizes.coverBox(900, 1600, 76));
    }

    @Test
    void aVeryWidePictureIsCappedAtThreeTimesItsShortSide() {
        assertArrayEquals(new int[] {228, 76}, MediaSizes.coverBox(10000, 1000, 76));
    }

    @Test
    void anUnknownSizeIsASquare() {
        assertArrayEquals(new int[] {76, 76}, MediaSizes.coverBox(0, 0, 76));
    }
}

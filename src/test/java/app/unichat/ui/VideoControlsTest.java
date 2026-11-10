package app.unichat.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoControlsTest {

    @Test
    void speedGoesUpAndDownTheListAndStaysAtTheEnds() {
        assertEquals(1.25, VideoControls.nextRate(1, 1));
        assertEquals(0.75, VideoControls.nextRate(1, -1));
        assertEquals(2, VideoControls.nextRate(2, 1));
        assertEquals(0.25, VideoControls.nextRate(0.25, -1));
        assertEquals(1.5, VideoControls.nextRate(1.3, 1), "from a speed not on the list, the nearest one's next");
    }

    @Test
    void speedsReadShort() {
        assertEquals("1×", VideoControls.rateLabel(1));
        assertEquals("1.5×", VideoControls.rateLabel(1.5));
        assertEquals("0.25×", VideoControls.rateLabel(0.25));
        assertEquals("2×", VideoControls.rateLabel(2));
    }

    @Test
    void seekingStaysInsideTheVideo() {
        assertEquals(0, VideoControls.clamp(-3, 60));
        assertEquals(60, VideoControls.clamp(75, 60));
        assertEquals(75, VideoControls.clamp(75, Double.NaN), "length not known yet");
    }

    @Test
    void aFrameIsNamedAfterTheVideoAndTheMoment() {
        assertEquals("clip-1m05s.png", VideoControls.frameName("clip.mp4", 65.4));
        assertEquals("video-0m00s.png", VideoControls.frameName(null, 0));
        assertEquals("a_b-0m03s.png", VideoControls.frameName("a/b", 3));
        assertEquals("clipgnp-0m03s.png", VideoControls.frameName("clip\u202Egnp.mp4", 3), "cleaned as saved files are");
        assertEquals("video-0m03s.png", VideoControls.frameName(".mp4", 3), "never a hidden file");
    }
}

package app.unichat.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadySoundTest {
    private static int peak(short[] s) {
        int p = 0;
        for (short v : s) p = Math.max(p, Math.abs(v));
        return p;
    }

    @Test
    void offOrSilentMakesNoSound() {
        assertEquals(0, ReadySound.samples(ReadySound.Tone.OFF, 80).length);
        assertEquals(0, ReadySound.samples(ReadySound.Tone.CHIME, 0).length);
    }

    @Test
    void everyToneIsShortAndNeverClips() {
        for (ReadySound.Tone t : ReadySound.Tone.values()) {
            if (t == ReadySound.Tone.OFF) continue;
            short[] s = ReadySound.samples(t, 100);
            assertTrue(s.length > 0 && s.length < ReadySound.RATE, t + " is under a second");
            assertTrue(peak(s) < Short.MAX_VALUE, t + " stays within range");
            assertTrue(Math.abs(s[s.length - 1]) < 50, t + " fades out instead of clicking");
        }
    }

    @Test
    void theVolumeMakesItLouder() {
        assertTrue(peak(ReadySound.samples(ReadySound.Tone.BELL, 90)) > peak(ReadySound.samples(ReadySound.Tone.BELL, 30)));
    }

    @Test
    void theSettingsKeepTheVolumeInRange() {
        AiSettings.setReadyVolume(250);
        assertEquals(100, AiSettings.readyVolume());
        AiSettings.setReadyVolume(-4);
        assertEquals(0, AiSettings.readyVolume());
        AiSettings.setReadyVolume(60);
    }
}

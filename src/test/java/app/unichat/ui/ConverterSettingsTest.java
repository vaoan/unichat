package app.unichat.ui;

import app.unichat.ui.ConverterSettings.State;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConverterSettingsTest {
    @Test
    void sayWhichOneIsInUseOrWhatHappensWithoutIt() {
        Path some = Path.of("ffmpeg");
        assertEquals(State.READY_OWN, ConverterSettings.state(some, false, true, true));
        assertEquals(State.READY_OURS, ConverterSettings.state(some, true, true, false));
        assertEquals(State.WILL_INSTALL, ConverterSettings.state(null, false, true, true));
        assertEquals(State.MISSING, ConverterSettings.state(null, false, true, false));
        assertEquals(State.NO_DOWNLOAD, ConverterSettings.state(null, false, false, true));
    }
}

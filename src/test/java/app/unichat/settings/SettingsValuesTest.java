package app.unichat.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsValuesTest {
    @TempDir Path dir;

    /** Every Settings a test made: their writes run in the background, and must end before the folder is deleted. */
    private final java.util.List<Settings> made = new java.util.ArrayList<>();

    private Settings made(Settings s) {
        made.add(s);
        return s;
    }

    @org.junit.jupiter.api.AfterEach
    void writesEnd() {
        made.forEach(Settings::flush);
    }

    @Test
    void numbersKeepTheirMinimumAndDefault() {
        Settings s = made(new Settings(dir));
        assertEquals(30, s.getInt("n", 30, 0));
        s.set("n", " 5 ");
        assertEquals(5, s.getInt("n", 30, 0));
        assertEquals(100, s.getInt("n", 1024, 100));
        s.set("n", "many");
        assertEquals(30, s.getInt("n", 30, 0));
    }

    @Test
    void anUnknownEnumNameIsTheDefault() {
        Settings s = made(new Settings(dir));
        assertEquals(Settings.Mode.SYSTEM, s.getEnum("m", Settings.Mode.SYSTEM));
        s.set("m", "ON");
        assertEquals(Settings.Mode.ON, s.getEnum("m", Settings.Mode.SYSTEM));
        s.set("m", "NOPE");
        assertEquals(Settings.Mode.OFF, s.getEnum("m", Settings.Mode.OFF));
    }

    @Test
    void theOldReadReceiptsSwitchDecidesUntilAChoiceIsMade() {
        Settings s = made(new Settings(dir));
        assertEquals(Settings.ReadReceipts.ALWAYS, s.readReceipts());
        s.set("privacy.readReceipts", "false");
        assertEquals(Settings.ReadReceipts.NEVER, s.readReceipts());
        s.setReadReceipts(Settings.ReadReceipts.ON_REPLY);
        s.flush();
        assertEquals(Settings.ReadReceipts.ON_REPLY, made(new Settings(dir)).readReceipts());
    }

    @Test
    void aSetKeepsOrderAndLeavesWithItsLastValue() {
        Settings s = made(new Settings(dir));
        assertTrue(s.getSet("k").isEmpty());
        s.addToSet("k", "b");
        s.addToSet("k", "a");
        s.addToSet("k", "b");
        assertEquals("b,a", s.getString("k", null));
        assertEquals(List.of("b", "a"), List.copyOf(s.getSet("k")));
        s.removeFromSet("k", "b");
        s.removeFromSet("k", "a");
        assertEquals(null, s.getString("k", null));
    }

    @Test
    void theWindowsPlaceStaysOnThisMachine() throws Exception {
        Settings s = made(new Settings(dir));
        s.setWindowGeometry(800, 600, 10, 20);
        s.flush();
        assertTrue(Files.readString(dir.resolve("session.properties")).contains("window.width=800.0"));
        assertFalse(Files.exists(dir.resolve("settings.properties")) && Files.readString(dir.resolve("settings.properties")).contains("window."));
        assertEquals(20.0, made(new Settings(dir)).getDouble("window.y", 0));
    }
}

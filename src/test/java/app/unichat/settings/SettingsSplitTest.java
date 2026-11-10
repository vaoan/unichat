package app.unichat.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SettingsSplitTest {
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

    private String read(String name) throws IOException { return Files.readString(dir.resolve(name)); }

    /**
     * A change never waits for the disk: set() ran on the UI thread and wrote and synced two files holding the lock
     * every reader needs. Holding that lock here, the writer can't have written anything yet.
     */
    @Test void aChangeIsWrittenOffTheCallersThreadAndFlushWaitsForIt() throws IOException {
        var s = made(new Settings(dir));
        synchronized (s) {
            s.set("ui.theme", "DARK");
            assertFalse(Files.exists(dir.resolve("settings.properties")), "written by the caller, holding the lock");
        }
        s.flush();
        assertTrue(read("settings.properties").contains("ui.theme=DARK"));
    }

    @Test void machineKeysLiveApart() throws IOException {
        var s = made(new Settings(dir));
        s.setMatrixSession("https://h", "@a:h", "TOKEN", "DEV");
        s.set("ui.theme", "DARK");
        s.set("gif.tenor.key", "K");
        s.set("gif.tenor.enabled", "true");
        s.set("discord.guilds.paused", "1");
        s.set("window.width", "900");
        s.set("app.update.lastCheck", "1");
        s.flush();
        assertFalse(read("settings.properties").contains("TOKEN"));
        assertTrue(read("settings.properties").contains("ui.theme"));
        assertTrue(read("settings.properties").contains("gif.tenor.enabled"));
        for (String k : new String[] {"gif.tenor.key", "discord.guilds.paused", "window.width", "app.update.lastCheck"}) {
            assertFalse(read("settings.properties").contains(k), k);
        }
        assertTrue(read("session.properties").contains("TOKEN"));
        assertTrue(read("session.properties").contains("gif.tenor.key"));
        assertEquals("TOKEN", made(new Settings(dir)).accessToken());
        assertEquals("DARK", made(new Settings(dir)).getString("ui.theme", null));
    }

    /**
     * The transcription command is run as it is: kept in the portable settings it went to the person's cloud, where
     * anyone able to write there chose what every computer of theirs runs (and a Mac's command broke Windows).
     */
    @Test void aCommandThisComputerRunsNeverTravels() throws IOException {
        var s = made(new Settings(dir));
        s.setSttCommand("/usr/local/bin/whisper {file}");
        s.set("stt.engine", "CUSTOM");
        s.flush();
        assertFalse(read("settings.properties").contains("stt."));
        assertTrue(read("session.properties").contains("stt.command"));
        assertEquals("/usr/local/bin/whisper {file}", made(new Settings(dir)).sttCommand());
    }

    @Test void anOldFileIsMigratedOnceAndTwiceIsHarmless() throws IOException {
        Files.writeString(dir.resolve("settings.properties"), "matrix.accessToken=TOKEN\nui.theme=DARK\n");
        Files.writeString(dir.resolve("settings.properties.docker-backup"), "matrix.accessToken=OLD\n");
        for (int i = 0; i < 2; i++) {
            var s = made(new Settings(dir));
            s.flush();
            assertEquals("TOKEN", s.accessToken());
            assertEquals("DARK", s.getString("ui.theme", null));
            assertTrue(read("session.properties").contains("TOKEN"));
            assertFalse(read("settings.properties").contains("TOKEN"));
            assertTrue(read("settings.properties").contains("ui.theme"));
            assertFalse(read("session.properties").contains("ui.theme"));
            assertFalse(Files.exists(dir.resolve("settings.properties.docker-backup")));
        }
    }

    @Test void aCrashBetweenWritesKeepsTheSession() throws IOException {
        Files.writeString(dir.resolve("session.properties"), "matrix.accessToken=NEW\n");
        Files.writeString(dir.resolve("settings.properties"), "matrix.accessToken=OLD\nui.theme=DARK\n");
        var s = made(new Settings(dir));
        s.flush();
        assertEquals("NEW", s.accessToken());
        assertFalse(read("settings.properties").contains("matrix.accessToken"));
        assertEquals("NEW", made(new Settings(dir)).accessToken());
    }

    @Test void aFailedSaveKeepsThePreviousFile() throws IOException {
        var s = made(new Settings(dir));
        s.set("ui.theme", "DARK");
        s.flush();
        String before = read("settings.properties");
        assumeTrue(dir.toFile().setWritable(false) && !Files.isWritable(dir));
        try {
            assertDoesNotThrow(() -> {
                s.set("ui.theme", "LIGHT");
                s.flush();
            });
            assertEquals(before, read("settings.properties"));
        } finally {
            dir.toFile().setWritable(true);
        }
    }

    @Test void theSessionFileIsOwnerOnlyEvenOverAWideLeftoverTemp() throws IOException {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path leftover = Files.writeString(dir.resolve("session.properties.tmp"), "old");
        Files.setPosixFilePermissions(leftover, PosixFilePermissions.fromString("rw-r--r--"));
        var s = made(new Settings(dir));
        s.setMatrixSession("https://h", "@a:h", "TOKEN", null);
        s.flush();
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("session.properties"))));
        assertFalse(Files.exists(leftover));
    }

    @Test void theSecretsNeverGoThroughALeftoverTemp() throws IOException {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path elsewhere = Files.writeString(dir.resolve("elsewhere"), "");
        Files.createSymbolicLink(dir.resolve("session.properties.tmp"), elsewhere);
        var s = made(new Settings(dir));
        s.setMatrixSession("https://h", "@a:h", "TOKEN", null);
        s.flush();
        assertEquals("", Files.readString(elsewhere));
        assertTrue(read("session.properties").contains("TOKEN"));
    }

    @Test void clearSessionEmptiesTheSessionFile() throws IOException {
        var s = made(new Settings(dir));
        s.setMatrixSession("https://h", "@a:h", "TOKEN", null);
        s.flush();
        assertTrue(read("session.properties").contains("TOKEN"));
        s.clearSession();
        s.flush();
        assertFalse(read("session.properties").contains("TOKEN"));
        assertNull(made(new Settings(dir)).accessToken());
    }

    /**
     * A file that couldn't be read at start (antivirus or OneDrive holding it a moment on Windows) was taken as empty,
     * and the first setting changed wrote that emptiness over it: logged out, every choice gone.
     */
    @Test void aFileThatCouldntBeReadIsNeverWrittenOver() throws IOException {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path settings = Files.writeString(dir.resolve("settings.properties"), "ui.theme=DARK\n");
        Path session = Files.writeString(dir.resolve("session.properties"), "matrix.accessToken=TOKEN\n");
        var locked = PosixFilePermissions.fromString("---------");
        var open = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(settings, locked);
        Files.setPosixFilePermissions(session, locked);
        var s = made(new Settings(dir));
        s.set("ui.accent", "#123456");
        s.set("window.width", "900");
        s.flush();
        Files.setPosixFilePermissions(settings, open);
        Files.setPosixFilePermissions(session, open);
        assertEquals("DARK", made(new Settings(dir)).getString("ui.theme", null));
        assertEquals("TOKEN", made(new Settings(dir)).accessToken());

        // Readable again by the next save: what was there and what changed meanwhile, both kept.
        Files.setPosixFilePermissions(settings, locked);
        var later = made(new Settings(dir));
        Files.setPosixFilePermissions(settings, open);
        later.set("ui.accent", "#654321");
        later.flush();
        var again = made(new Settings(dir));
        assertEquals("DARK", again.getString("ui.theme", null));
        assertEquals("#654321", again.getString("ui.accent", null));
    }
}

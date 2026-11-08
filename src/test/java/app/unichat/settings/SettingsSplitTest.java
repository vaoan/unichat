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

    private String read(String name) throws IOException { return Files.readString(dir.resolve(name)); }

    @Test void machineKeysLiveApart() throws IOException {
        var s = new Settings(dir);
        s.setMatrixSession("https://h", "@a:h", "TOKEN", "DEV");
        s.set("ui.theme", "DARK");
        s.set("gif.tenor.key", "K");
        s.set("gif.tenor.enabled", "true");
        s.set("discord.guilds.paused", "1");
        s.set("window.width", "900");
        s.set("app.update.lastCheck", "1");
        assertFalse(read("settings.properties").contains("TOKEN"));
        assertTrue(read("settings.properties").contains("ui.theme"));
        assertTrue(read("settings.properties").contains("gif.tenor.enabled"));
        for (String k : new String[] {"gif.tenor.key", "discord.guilds.paused", "window.width", "app.update.lastCheck"}) {
            assertFalse(read("settings.properties").contains(k), k);
        }
        assertTrue(read("session.properties").contains("TOKEN"));
        assertTrue(read("session.properties").contains("gif.tenor.key"));
        assertEquals("TOKEN", new Settings(dir).accessToken());
        assertEquals("DARK", new Settings(dir).getString("ui.theme", null));
    }

    @Test void anOldFileIsMigratedOnceAndTwiceIsHarmless() throws IOException {
        Files.writeString(dir.resolve("settings.properties"), "matrix.accessToken=TOKEN\nui.theme=DARK\n");
        Files.writeString(dir.resolve("settings.properties.docker-backup"), "matrix.accessToken=OLD\n");
        for (int i = 0; i < 2; i++) {
            var s = new Settings(dir);
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
        var s = new Settings(dir);
        assertEquals("NEW", s.accessToken());
        assertFalse(read("settings.properties").contains("matrix.accessToken"));
        assertEquals("NEW", new Settings(dir).accessToken());
    }

    @Test void aFailedSaveKeepsThePreviousFile() throws IOException {
        var s = new Settings(dir);
        s.set("ui.theme", "DARK");
        String before = read("settings.properties");
        assumeTrue(dir.toFile().setWritable(false) && !Files.isWritable(dir));
        try {
            assertDoesNotThrow(() -> s.set("ui.theme", "LIGHT"));
            assertEquals(before, read("settings.properties"));
        } finally {
            dir.toFile().setWritable(true);
        }
    }

    @Test void theSessionFileIsOwnerOnlyEvenOverAWideLeftoverTemp() throws IOException {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path leftover = Files.writeString(dir.resolve("session.properties.tmp"), "old");
        Files.setPosixFilePermissions(leftover, PosixFilePermissions.fromString("rw-r--r--"));
        new Settings(dir).setMatrixSession("https://h", "@a:h", "TOKEN", null);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("session.properties"))));
        assertFalse(Files.exists(leftover));
    }

    @Test void theSecretsNeverGoThroughALeftoverTemp() throws IOException {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path elsewhere = Files.writeString(dir.resolve("elsewhere"), "");
        Files.createSymbolicLink(dir.resolve("session.properties.tmp"), elsewhere);
        new Settings(dir).setMatrixSession("https://h", "@a:h", "TOKEN", null);
        assertEquals("", Files.readString(elsewhere));
        assertTrue(read("session.properties").contains("TOKEN"));
    }

    @Test void clearSessionEmptiesTheSessionFile() throws IOException {
        var s = new Settings(dir);
        s.setMatrixSession("https://h", "@a:h", "TOKEN", null);
        s.clearSession();
        assertFalse(read("session.properties").contains("TOKEN"));
        assertNull(new Settings(dir).accessToken());
    }
}

package app.unichat.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SyncedFileTest {
    @TempDir Path dir;

    private boolean posix() { return dir.getFileSystem().supportedFileAttributeViews().contains("posix"); }

    @Test
    void createsAndReplaces() throws Exception {
        Path f = dir.resolve("a.json");
        SyncedFile.replace(f, new byte[]{1}, false);
        SyncedFile.replace(f, new byte[]{2}, false);
        assertEquals(2, Files.readAllBytes(f)[0]);
        assertFalse(Files.exists(dir.resolve("a.json.tmp")));
    }

    @Test
    void secretsAreOnlyTheOwners() throws Exception {
        assumeTrue(posix());
        Path f = dir.resolve("secrets.properties");
        SyncedFile.replace(f, new byte[]{1}, true);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
    }

    @Test
    void aLooseFileBecomesPrivate() throws Exception {
        assumeTrue(posix());
        Path f = dir.resolve("secrets.properties");
        Files.writeString(f, "old");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        SyncedFile.replace(f, new byte[]{1}, true);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
    }

    /** A crash leaves a temp file behind: its loose mode, or a link in its place, must not decide anything. */
    @Test
    void aLeftoverTempDecidesNothing() throws Exception {
        assumeTrue(posix());
        Path f = dir.resolve("s");
        Path leftover = dir.resolve("s.tmp");
        Files.writeString(leftover, "old");
        Files.setPosixFilePermissions(leftover, PosixFilePermissions.fromString("rw-rw-rw-"));
        SyncedFile.replace(f, new byte[]{1}, true);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));

        Path elsewhere = dir.resolve("elsewhere");
        Files.writeString(elsewhere, "mine");
        Files.createSymbolicLink(leftover, elsewhere);
        SyncedFile.replace(f, new byte[]{2}, true);
        assertEquals("mine", Files.readString(elsewhere));
        assertEquals(2, Files.readAllBytes(f)[0]);
    }

    @Test
    void aFailureLeavesTheOldThingAndNoTemp() throws Exception {
        Path f = Files.createDirectories(dir.resolve("full"));
        Files.writeString(f.resolve("inside"), "keep");
        assertThrows(IOException.class, () -> SyncedFile.replace(f, new byte[]{1}, false));
        assertTrue(Files.exists(f.resolve("inside")));
        assertFalse(Files.exists(dir.resolve("full.tmp")));
    }
}

package app.unichat.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class FoldersTest {
    @Test
    void whatsInAFolderLongerThanItsTimeGoesTheRestStays(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path old = java.nio.file.Files.createDirectories(tmp.resolve("capture-1"));
        java.nio.file.Files.writeString(old.resolve("a.png"), "x");
        java.nio.file.Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(2))));
        Path recent = java.nio.file.Files.createDirectories(tmp.resolve("capture-2"));
        Folders.deleteOlderThan(tmp, java.time.Duration.ofDays(1));
        assertFalse(java.nio.file.Files.exists(old), "a day old: gone, with what was in it");
        assertTrue(java.nio.file.Files.exists(recent));
        Folders.deleteOlderThan(tmp.resolve("nada"), java.time.Duration.ofDays(1)); // no folder: nothing to do
    }

    @Test
    void aPrivateFolderIsTheOwnersAlone(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path dir = Folders.ownerOnly(tmp.resolve("texts/here"));
        assertTrue(java.nio.file.Files.isDirectory(dir));
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(dir)));
        }
        assertEquals(dir, Folders.ownerOnly(dir), "there already: fine");
    }

    @TempDir Path dir;

    private Path tree() throws IOException {
        Path root = Files.createDirectories(dir.resolve("perfil/Default/Network"));
        Files.writeString(root.resolve("Cookies"), "x");
        Files.writeString(dir.resolve("perfil/Local State"), "y");
        return dir.resolve("perfil");
    }

    @Test
    void deletesAWholeTree() throws Exception {
        Path root = tree();
        Folders.delete(root);
        assertFalse(Files.exists(root));
    }

    @Test
    void aMissingFolderIsFine() {
        assertDoesNotThrow(() -> Folders.delete(dir.resolve("nada")));
        assertDoesNotThrow(() -> Folders.deleteQuietly(dir.resolve("nada")));
        assertDoesNotThrow(() -> Folders.deleteQuietly(null));
    }

    @Test
    void aLinkIsRemovedNotFollowed() throws Exception {
        assumeFalse(Os.windows(), "links need privileges there");
        Path outside = Files.createDirectories(dir.resolve("afuera"));
        Files.writeString(outside.resolve("mio.txt"), "z");
        Path root = tree();
        Files.createSymbolicLink(root.resolve("enlace"), outside);

        Folders.delete(root);

        assertFalse(Files.exists(root));
        assertTrue(Files.exists(outside.resolve("mio.txt")));
    }

    /** A signed-in browser profile is what this is for: one stubborn folder mustn't save the cookies beside it. */
    @Test
    void whatResistsDoesntSaveTheRest() throws Exception {
        assumeFalse(Os.windows(), "file modes");
        Path root = tree();
        Path before = Files.createDirectories(root.resolve("a"));
        Path locked = Files.createDirectories(root.resolve("m"));
        Path after = Files.createDirectories(root.resolve("z"));
        Files.writeString(before.resolve("sesion"), "1");
        Files.writeString(locked.resolve("dentro"), "2");
        Files.writeString(after.resolve("sesion"), "3");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            Folders.deleteQuietly(root);
            assertTrue(Files.exists(locked), "this one resisted");
            assertFalse(Files.exists(before), "before it");
            assertFalse(Files.exists(after), "after it");
            assertFalse(Files.exists(root.resolve("Default")));

            assertThrows(IOException.class, () -> Folders.delete(root), "the throwing one says so");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }
}

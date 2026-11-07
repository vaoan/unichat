package app.unichat.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class FoldersTest {
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

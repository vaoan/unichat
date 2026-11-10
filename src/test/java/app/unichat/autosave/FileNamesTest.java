package app.unichat.autosave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileNamesTest {
    @Test
    void aChatsFolderIsSafeAndNeverEmpty() {
        assertEquals("Familia 🏡", FileNames.folder("Familia 🏡"));
        assertEquals("a_b_c", FileNames.folder("a/b\\c"), "never a folder inside another");
        assertEquals("_", FileNames.folder(".."));
        assertEquals("_", FileNames.folder(null));
        assertEquals("_CON", FileNames.folder("CON"), "a Windows device name");
    }

    @Test
    void aTakenNameGetsTheNextNumber(@TempDir Path dir) throws Exception {
        assertEquals(dir.resolve("foto.jpg"), FileNames.free(dir, "foto.jpg"));
        Files.writeString(dir.resolve("foto.jpg"), "");
        Files.writeString(dir.resolve("foto (2).jpg"), "");
        assertEquals(dir.resolve("foto (3).jpg"), FileNames.free(dir, "foto.jpg"));
        Files.writeString(dir.resolve("LEEME"), "");
        assertEquals(dir.resolve("LEEME (2)"), FileNames.free(dir, "LEEME"));
    }

    @Test
    void aFolderUnderHomeReadsFromTilde() {
        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home"));
        assertEquals("~" + java.io.File.separator + "Downloads" + java.io.File.separator + "Unichat",
                AutoSave.shown(home.resolve("Downloads").resolve("Unichat")));
        assertEquals(java.nio.file.Path.of("/elsewhere/x").toString(), AutoSave.shown(java.nio.file.Path.of("/elsewhere/x")));
    }
}

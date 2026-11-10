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
                SaveFolders.shown(home.resolve("Downloads").resolve("Unichat")));
        assertEquals(java.nio.file.Path.of("/elsewhere/x").toString(), SaveFolders.shown(java.nio.file.Path.of("/elsewhere/x")));
    }

    @Test
    void savedFilesCarryTheMessagesIdAndAreKnownAgain(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        app.unichat.model.Message m = new app.unichat.model.Message("$AbC_d-9:ejemplo.org", "!c", "@a", "Ana", false,
                java.time.Instant.parse("2026-11-03T10:00:00Z"));
        m.setType(app.unichat.model.MessageType.VIDEO);
        m.setFileName("IMG_3944.MOV");
        org.junit.jupiter.api.Assertions.assertEquals("IMG_3944 [AbC_d-9_ejemplo.org].MOV", FileNames.forSaving(m, java.time.ZoneOffset.UTC),
                "the original name and the Matrix id, without $ nor what Windows refuses");
        org.junit.jupiter.api.Assertions.assertFalse(FileNames.savedIn(dir, m));
        java.nio.file.Files.writeString(dir.resolve(".IMG_3944 [AbC_d-9_ejemplo.org].MOV.part"), "half");
        org.junit.jupiter.api.Assertions.assertFalse(FileNames.savedIn(dir, m), "half-written doesn't count");
        java.nio.file.Files.writeString(dir.resolve("IMG_3944 [AbC_d-9_ejemplo.org] (2).MOV"), "whole");
        org.junit.jupiter.api.Assertions.assertTrue(FileNames.savedIn(dir, m));
    }

    @Test
    void aNameCantHideItsRealExtension() {
        assertEquals("invoicefdp.exe", FileNames.safe("invoice\u202Efdp.exe"), "a right-to-left override showed it as a PDF");
        assertEquals("ab.txt", FileNames.safe("a\u200Bb\u2066.txt"), "nor any other invisible format character");
        assertEquals("Familia 🏡", FileNames.safe("Familia 🏡"));
    }

    @Test
    void aNameStartingWithADotIsntHiddenAndIsKnownAgain(@TempDir Path dir) throws Exception {
        assertEquals("profile", FileNames.safe(".profile"));
        assertEquals("Trash", FileNames.folder(".Trash"), "nor a chat's folder");
        assertEquals("_", FileNames.folder("..."));
        app.unichat.model.Message m = new app.unichat.model.Message("$id", "!c", "@a", "Ana", false,
                java.time.Instant.parse("2026-11-03T10:00:00Z"));
        m.setType(app.unichat.model.MessageType.FILE);
        m.setFileName(".profile");
        String saved = FileNames.forSaving(m, java.time.ZoneOffset.UTC);
        assertEquals("profile [id]", saved);
        Files.writeString(dir.resolve(saved), "x");
        org.junit.jupiter.api.Assertions.assertTrue(FileNames.savedIn(dir, m), "a hidden one was saved again on every explore");
        m.setFileName("...");
        org.junit.jupiter.api.Assertions.assertTrue(FileNames.forMessage(m, java.time.ZoneOffset.UTC).startsWith("archivo-20261103"),
                "nothing left: the made-up name");
    }

    @Test
    void aFileWindowsActsOnJustByListingTheFolderIsSavedInert(@TempDir Path dir) throws Exception {
        // Explorer reads a .url's or .library-ms's icon address as soon as it shows the folder: a \\host\share there
        // sends the login's NTLM hash to whoever sent the file. Saved as text, it's still readable and does nothing.
        for (String name : java.util.List.of("informe.url", "Docs.library-ms", "x.searchConnector-ms", "acceso.LNK", "a.scf",
                "b.search-ms", "c.website", "d.theme", "e.themepack", "f.appref-ms", "g.settingcontent-ms")) {
            app.unichat.model.Message m = new app.unichat.model.Message("$id", "!c", "@a", "Ana", false,
                    java.time.Instant.parse("2026-11-03T10:00:00Z"));
            m.setType(app.unichat.model.MessageType.FILE);
            m.setFileName(name);
            String saved = FileNames.forSaving(m, java.time.ZoneOffset.UTC);
            int dot = name.lastIndexOf('.');
            assertEquals(name.substring(0, dot) + " [id]" + name.substring(dot) + ".txt", saved, name);
            Path file = Files.writeString(dir.resolve(saved), "x");
            org.junit.jupiter.api.Assertions.assertTrue(FileNames.savedIn(dir, m), "known again, never saved twice: " + name);
            Files.delete(file);
        }
        app.unichat.model.Message ok = new app.unichat.model.Message("$id", "!c", "@a", "Ana", false,
                java.time.Instant.parse("2026-11-03T10:00:00Z"));
        ok.setType(app.unichat.model.MessageType.FILE);
        ok.setFileName("factura.pdf");
        assertEquals("factura [id].pdf", FileNames.forSaving(ok, java.time.ZoneOffset.UTC), "anything else as it came");
    }
}

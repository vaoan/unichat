package app.unichat.autosave;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "a fixed path the test passes in as input; nothing is read or written there")
    void aFolderUnderHomeReadsFromTilde() {
        Path home = Path.of(System.getProperty("user.home"));
        assertEquals("~" + java.io.File.separator + "Downloads" + java.io.File.separator + "Unichat",
                SaveFolders.shown(home.resolve("Downloads").resolve("Unichat")));
        assertEquals(Path.of("/elsewhere/x").toString(), SaveFolders.shown(Path.of("/elsewhere/x")));
    }

    @Test
    void savedFilesCarryTheMessagesIdAndAreKnownAgain(@TempDir Path dir) throws Exception {
        app.unichat.model.Message m = new app.unichat.model.Message("$AbC_d-9:ejemplo.org", "!c", "@a", "Ana", false,
                java.time.Instant.parse("2026-11-03T10:00:00Z"));
        m.setType(app.unichat.model.MessageType.VIDEO);
        m.setFileName("IMG_3944.MOV");
        assertEquals("IMG_3944 [AbC_d-9_ejemplo.org].MOV", FileNames.forSaving(m, java.time.ZoneOffset.UTC),
                "the original name and the Matrix id, without $ nor what Windows refuses");
        org.junit.jupiter.api.Assertions.assertFalse(FileNames.savedIn(dir, m));
        Files.writeString(dir.resolve(".IMG_3944 [AbC_d-9_ejemplo.org].MOV.part"), "half");
        org.junit.jupiter.api.Assertions.assertFalse(FileNames.savedIn(dir, m), "half-written doesn't count");
        Files.writeString(dir.resolve("IMG_3944 [AbC_d-9_ejemplo.org] (2).MOV"), "whole");
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
        assertEquals("IMG__04d.JPG", FileNames.safe("IMG_%04d.JPG"), "ffmpeg reads a %d as a number pattern: another file");
        assertEquals("d_d", FileNames.folder("d%d"), "folders too");
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
        org.junit.jupiter.api.Assertions.assertTrue(FileNames.forMessage(m, java.time.ZoneOffset.UTC).startsWith("file-20261103"),
                "nothing left: the made-up name");
    }

    /** A name on disk isn't UI: the same in every language, and what was saved under the Spanish names is known again. */
    @Test
    void savedFilesAreNamedInEnglishWhateverTheUiLanguage(@TempDir Path dir) throws Exception {
        assertEquals("es", app.unichat.support.I18n.locale().getLanguage(), "the tests run in Spanish");
        var kinds = java.util.Map.of(app.unichat.model.MessageType.IMAGE, "image-20261103-100000.jpg",
                app.unichat.model.MessageType.VIDEO, "video-20261103-100000.mp4",
                app.unichat.model.MessageType.AUDIO, "audio-20261103-100000.ogg",
                app.unichat.model.MessageType.STICKER, "sticker-20261103-100000.webp",
                app.unichat.model.MessageType.FILE, "file-20261103-100000.pdf");
        var mimes = java.util.Map.of(app.unichat.model.MessageType.IMAGE, "image/jpeg", app.unichat.model.MessageType.VIDEO, "video/mp4",
                app.unichat.model.MessageType.AUDIO, "audio/ogg", app.unichat.model.MessageType.STICKER, "image/webp",
                app.unichat.model.MessageType.FILE, "application/pdf");
        for (var k : kinds.entrySet()) {
            app.unichat.model.Message m = new app.unichat.model.Message("$id" + k.getKey(), "!c", "@a", "Ana", false,
                    java.time.Instant.parse("2026-11-03T10:00:00Z"));
            m.setType(k.getKey());
            m.setMimeType(mimes.get(k.getKey()));
            assertEquals(k.getValue(), FileNames.forMessage(m, java.time.ZoneOffset.UTC));
        }
        app.unichat.model.Message old = new app.unichat.model.Message("$viejo", "!c", "@a", "Ana", false,
                java.time.Instant.parse("2026-11-03T10:00:00Z"));
        old.setType(app.unichat.model.MessageType.IMAGE);
        old.setMimeType("image/jpeg");
        Files.writeString(dir.resolve("imagen-20261103-100000 [viejo].jpg"), "x");
        org.junit.jupiter.api.Assertions.assertTrue(FileNames.savedIn(dir, old), "found by its [id]: not saved again");
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

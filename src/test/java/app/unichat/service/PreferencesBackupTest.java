package app.unichat.service;

import app.unichat.service.PreferencesFile.Flag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** preferences.json is the only copy of the person's choices: a damaged one falls back to the previous version. */
class PreferencesBackupTest {
    private static final String A = "telegram:1", B = "telegram:2";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    private Path prefs() { return dir.resolve("preferences.json"); }
    private Path bak() { return dir.resolve("preferences.json.bak"); }
    private PreferencesFile open() { return new PreferencesFile(prefs(), dir.resolve("server-seen.json")); }
    private static String warning(PreferencesFile p) { return new FolderActions(p, () -> { }).preferencesWarning(); }

    /** The last write is in the main file, the one before it in the backup. */
    private void twoSaves() {
        var p = open();
        p.choose(A, Flag.MUTED, true);
        p.choose(B, Flag.FAVORITE, true);
    }

    private long setAsideFiles() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().contains(".bad-")).count();
        }
    }

    @Test
    void replacingTheFileKeepsThePreviousVersion() throws IOException {
        twoSaves();
        var saved = JSON.readTree(bak().toFile()).path("chats");
        assertTrue(saved.path(A).path("muted").asBoolean());
        assertFalse(saved.has(B), "the backup is the version before the last change");
        assertTrue(JSON.readTree(prefs().toFile()).path("chats").has(B));
        assertFalse(Files.exists(dir.resolve("server-seen.json.bak")), "only the person's choices get a backup");
    }

    @Test
    void anEmptyFileAfterAPowerCutComesBackFromTheBackup() throws IOException {
        twoSaves();
        Files.writeString(prefs(), "");
        var p = open();
        assertTrue(p.current(A).muted());
        assertFalse(p.current(B).favorite(), "the last change is what's missing");
        assertTrue(p.recoveredFromBackup());
        assertEquals("", Files.readString(dir.resolve(p.setAside())), "the damaged file is set aside, not overwritten");
        String said = warning(p);
        assertTrue(said.contains("copia anterior") && said.contains("último cambio") && said.contains(p.setAside()), said);
    }

    @Test
    void aTruncatedFileComesBackFromTheBackup() throws IOException {
        twoSaves();
        String whole = Files.readString(prefs());
        Files.writeString(prefs(), whole.substring(0, whole.length() / 2));
        var p = open();
        assertTrue(p.current(A).muted());
        assertTrue(p.recoveredFromBackup());
    }

    @Test
    void whatCameBackIsSavedEvenIfNothingChanges() throws IOException {
        twoSaves();
        Files.writeString(prefs(), "{roto");
        open();
        var again = open();
        assertTrue(again.current(A).muted(), "closing right after recovering must not lose it");
        assertFalse(again.recoveredFromBackup());
        assertNull(warning(again));
        assertEquals(1, setAsideFiles());
    }

    @Test
    void aFileThatHalfLoadsLeavesNothingBehind() throws IOException {
        twoSaves();
        Files.writeString(prefs(), "{\"version\":1,\"mine\":1,\"chats\":{\"telegram:9\":{\"pinned\":true},\"x\":3}}");
        var p = open();
        assertFalse(p.current("telegram:9").pinned());
        assertTrue(p.current(A).muted());
        p.flush();
        assertFalse(JSON.readTree(prefs().toFile()).has("mine"));
    }

    @Test
    void whenBothAreDamagedItStartsEmptyAndSaysSo() throws IOException {
        twoSaves();
        Files.writeString(prefs(), "{roto");
        Files.writeString(bak(), "tambien roto");
        var p = open();
        assertFalse(p.recoveredFromBackup());
        assertFalse(p.current(A).muted());
        assertEquals("{roto", Files.readString(dir.resolve(p.setAside())));
        assertTrue(warning(p).contains("empiezan vacías"), warning(p));
    }

    @Test
    void withNoMainFileTheBackupIsNotBroughtBack() throws IOException {
        twoSaves();
        Files.delete(prefs());
        assertFalse(open().current(A).muted(), "deleting the file is how a person starts over");
    }

    @Test
    void aBackupThatCannotBeWrittenBlocksNothing() throws IOException {
        var p = open();
        p.choose(A, Flag.MUTED, true);
        Files.createDirectories(bak().resolve("in-the-way")); // a file Windows or an antivirus won't let go of
        p.choose(B, Flag.FAVORITE, true);
        var chats = JSON.readTree(prefs().toFile()).path("chats");
        assertTrue(chats.path(A).path("muted").asBoolean(), "the good file isn't lost");
        assertTrue(chats.path(B).path("favorite").asBoolean(), "the change is saved");
        assertFalse(Files.exists(dir.resolve("preferences.json.bak.tmp")));
    }

    @Test
    void anUnmovableDamagedFileIsKeptAndItsBackupStillLoads() throws IOException {
        twoSaves();
        Files.writeString(prefs(), "{roto");
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        PreferencesFile p;
        try {
            assumeTrue(!Files.isWritable(dir));
            p = open();
            assertTrue(p.current(A).muted());
            assertTrue(p.unreadable());
            assertTrue(warning(p).contains("no se guardará nada"), warning(p));
            p.choose(B, Flag.PINNED, true);
            assertEquals("{roto", Files.readString(prefs()));
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        p.flush();
        assertFalse(p.unreadable());
        assertEquals("{roto", Files.readString(dir.resolve(p.setAside())));
        var chats = JSON.readTree(prefs().toFile()).path("chats");
        assertTrue(chats.path(A).path("muted").asBoolean());
        assertTrue(chats.path(B).path("pinned").asBoolean());
    }

    /** A non-empty directory where the temp goes: the write fails while renames still work, as on a full disk. */
    private Path blockWrites() throws IOException {
        return Files.createDirectories(dir.resolve("preferences.json.tmp").resolve("in-the-way"));
    }

    private void unblockWrites(Path obstacle) throws IOException {
        Files.delete(obstacle);
        Files.delete(obstacle.getParent());
    }

    @Test
    void aFailedRewriteAfterRecoveringLosesNothing() throws Exception {
        twoSaves();
        Files.writeString(prefs(), "{roto");
        String good = Files.readString(bak());
        Path obstacle = blockWrites();

        var first = open();
        assertTrue(first.current(A).muted());
        assertEquals("{roto", Files.readString(prefs()), "the damaged file stays until its replacement is on disk");
        assertEquals(good, Files.readString(bak()));
        var said = new LinkedBlockingQueue<String>();
        new FolderActions(first, () -> { }).onSaveFailure(said::add);
        String text = said.poll(5, TimeUnit.SECONDS);
        assertNotNull(text, "the person must hear the recovery isn't saved");
        assertTrue(text.contains("copia anterior") && text.contains("No se pudieron guardar"), text);
        first.choose(B, Flag.PINNED, true);
        first.choose(B, Flag.ARCHIVED, true);
        assertEquals(good, Files.readString(bak()), "failed saves leave the backup alone");

        var second = open();
        assertTrue(second.current(A).muted(), "the next start recovers again");
        assertEquals(good, Files.readString(bak()));
        assertEquals(1, setAsideFiles(), "the same damage is set aside once");
        assertEquals(first.setAside(), second.setAside());

        unblockWrites(obstacle);
        var third = open();
        assertTrue(third.current(A).muted());
        assertEquals(1, setAsideFiles());
        assertEquals("{roto", Files.readString(dir.resolve(third.setAside())));
        var fourth = open();
        assertTrue(fourth.current(A).muted());
        assertFalse(fourth.recoveredFromBackup(), "the main file is whole again");
        assertTrue(JSON.readTree(bak().toFile()).path("chats").path(A).path("muted").asBoolean());
    }

    @Test
    void recoveringNeverCopiesTheDamagedFileOverTheBackup() throws Exception {
        twoSaves();
        Files.writeString(prefs(), "{roto");
        String good = Files.readString(bak());

        open();
        assertEquals(good, Files.readString(bak()), "a recovery that works straight away");

        Files.writeString(prefs(), "{roto otra vez");
        Path obstacle = blockWrites();
        var p = open();
        unblockWrites(obstacle);
        p.flush();
        assertEquals(good, Files.readString(bak()), "the retry replaces the damaged file without backing it up");
        assertTrue(JSON.readTree(prefs().toFile()).path("chats").path(A).path("muted").asBoolean());
        p.choose(B, Flag.PINNED, true);
        assertTrue(JSON.readTree(bak().toFile()).path("chats").path(A).path("muted").asBoolean(), "from here on, as always");
    }

    @Test
    void differentDamageGetsItsOwnCopy() throws IOException {
        twoSaves();
        Files.writeString(prefs(), "{roto");
        String firstAside = open().setAside();
        Files.writeString(prefs(), "{roto de otra forma");
        String secondAside = open().setAside();
        assertNotEquals(firstAside, secondAside);
        assertEquals(2, setAsideFiles());
    }

    @Test
    void aSuccessfulRecoveryDoesNotWarnAboutSaving() throws Exception {
        twoSaves();
        Files.writeString(prefs(), "{roto");
        var said = new LinkedBlockingQueue<String>();
        new FolderActions(open(), () -> { }).onSaveFailure(said::add);
        assertNull(said.poll(200, TimeUnit.MILLISECONDS));
    }
}

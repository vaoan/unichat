package app.unichat.service;

import app.unichat.service.PreferencesFile.Effective;
import app.unichat.service.PreferencesFile.Flag;
import app.unichat.service.PreferencesFile.ServerValues;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PreferencesFileTest {
    private static final String K = "telegram:1";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    private Path prefs() { return dir.resolve("preferences.json"); }
    private Path seen() { return dir.resolve("server-seen.json"); }
    private final List<PreferencesFile> opened = new ArrayList<>();

    /** Like a restart: what the earlier ones hold is on disk first. */
    private PreferencesFile open() {
        flushAll();
        PreferencesFile p = new PreferencesFile(prefs(), seen());
        opened.add(p);
        return p;
    }

    @AfterEach
    void flushAll() {
        opened.forEach(PreferencesFile::flush);
    }

    private static ServerValues on(Flag f) {
        return new ServerValues(f == Flag.ARCHIVED, f == Flag.MUTED, f == Flag.FAVORITE, f == Flag.LOW_PRIORITY,
                f == Flag.PINNED, null, false);
    }

    private static ServerValues off() { return new ServerValues(false, false, false, false, false, null, false); }

    private static ServerValues unknown() { return new ServerValues(null, null, null, null, null, null, false); }

    private static ServerValues reminder(Instant at) {
        return new ServerValues(null, null, null, null, null, at, true);
    }

    private JsonNode read() throws IOException {
        flushAll();
        return JSON.readTree(prefs().toFile());
    }

    @Test
    void serverValueEntersWhenTheFileHasNothing() throws IOException {
        var p = open();
        assertTrue(p.reconcile(K, on(Flag.ARCHIVED)).archived());
        p.flush();
        JsonNode e = read().path("chats").path(K);
        assertTrue(e.path("archived").asBoolean());
        assertEquals("archived", e.path("fromServer").get(0).asText());
    }

    @Test
    void followsThePhoneBothWaysUntilTouched() {
        var p = open();
        assertTrue(p.reconcile(K, on(Flag.ARCHIVED)).archived());
        assertFalse(p.reconcile(K, off()).archived());
    }

    @Test
    void aNewServerDoesNotEraseWhatWasCopied() throws IOException {
        var a = open();
        a.reconcile(K, on(Flag.ARCHIVED));
        a.flush();
        Path other = Files.createDirectory(dir.resolve("b"));
        Files.copy(prefs(), other.resolve("preferences.json"));
        var b = new PreferencesFile(other.resolve("preferences.json"), other.resolve("seen.json"));
        assertTrue(b.reconcile(K, off()).archived());
    }

    @Test
    void aPersonsChoiceBeatsTheServer() {
        var p = open();
        p.choose(K, Flag.ARCHIVED, false);
        assertFalse(p.reconcile(K, on(Flag.ARCHIVED)).archived());
        var q = PreferencesFile.inMemory();
        q.reconcile(K, on(Flag.ARCHIVED));
        q.choose(K, Flag.ARCHIVED, true);
        assertTrue(q.reconcile(K, off()).archived());
    }

    @Test
    void choosingRemovesFromServerMarker() throws IOException {
        var p = open();
        p.reconcile(K, on(Flag.ARCHIVED));
        p.choose(K, Flag.ARCHIVED, true);
        p.flush();
        assertTrue(read().path("chats").path(K).path("fromServer").isMissingNode());
        assertTrue(p.reconcile(K, off()).archived());
    }

    @Test
    void explicitFalseSurvivesARestart() {
        open().choose(K, Flag.ARCHIVED, false);
        assertFalse(open().reconcile(K, on(Flag.ARCHIVED)).archived());
    }

    @Test
    void nothingHeardChangesNothing() throws IOException {
        var p = open();
        p.choose(K, Flag.ARCHIVED, true);
        assertTrue(p.reconcile(K, unknown()).archived());
        p.reconcile("telegram:2", unknown());
        p.flush();
        assertTrue(read().path("chats").path("telegram:2").isMissingNode());
        var q = PreferencesFile.inMemory();
        q.reconcile(K, unknown());
        assertFalse(q.current(K).archived());
    }

    @Test
    void followedValueIsNotAChoice() {
        var p = open();
        p.follow(K, Flag.ARCHIVED, true);
        assertTrue(p.reconcile(K, on(Flag.ARCHIVED)).archived());
        assertFalse(p.reconcile(K, off()).archived());
    }

    @Test
    void muteChosenHereIsReported() {
        var p = open();
        assertTrue(p.reconcile(K, off()).muteChosenHere() == false);
        p.choose(K, Flag.MUTED, true);
        assertTrue(p.current(K).muteChosenHere());
        var q = PreferencesFile.inMemory();
        assertFalse(q.reconcile(K, on(Flag.MUTED)).muteChosenHere());
    }

    @Test
    void reminderImportedOnlyWhenAbsent() {
        Instant ts = Instant.parse("2026-11-07T14:00:00Z");
        var p = open();
        assertEquals(ts, p.reconcile(K, reminder(ts)).reminder());
        p.chooseReminder(K, null);
        assertNull(p.reconcile(K, reminder(ts)).reminder());
        assertNull(open().reconcile(K, reminder(ts)).reminder());
    }

    @Test
    void unknownChatEntriesAreKept() throws IOException {
        Files.writeString(prefs(), "{\"version\":1,\"chats\":{\"whatsapp:9\":{\"favorite\":true}},\"folders\":[]}");
        var p = open();
        p.choose(K, Flag.MUTED, true);
        JsonNode chats = read().path("chats");
        assertTrue(chats.path("whatsapp:9").path("favorite").asBoolean());
        assertTrue(chats.path(K).path("muted").asBoolean());
    }

    @Test
    void rekeyMovesEntryAndFolders() {
        var p = open();
        p.choose("matrix:!a", Flag.PINNED, true);
        String f = p.createFolder("Work");
        p.setInFolder(f, "matrix:!a", true);
        p.rekey("matrix:!a", K);
        assertTrue(p.current(K).pinned());
        assertFalse(p.current("matrix:!a").pinned());
        assertEquals(List.of(K), p.folders().get(0).chatKeys());

        p.choose("matrix:!b", Flag.PINNED, true);
        p.choose("matrix:!b", Flag.FAVORITE, true);
        p.choose("telegram:2", Flag.PINNED, false);
        p.rekey("matrix:!b", "telegram:2");
        assertFalse(p.current("telegram:2").pinned());
        assertTrue(p.current("telegram:2").favorite());
    }

    @Test
    void aChatCanBeInSeveralFolders() {
        var p = open();
        String a = p.createFolder("A"), b = p.createFolder("B");
        p.setInFolder(a, K, true);
        p.setInFolder(b, K, true);
        assertTrue(p.folders().stream().allMatch(f -> f.chatKeys().contains(K)));
        p.setInFolder(a, K, false);
        assertFalse(p.folders().get(0).chatKeys().contains(K));
        assertTrue(p.folders().get(1).chatKeys().contains(K));
    }

    @Test
    void folderNamesMustNotBeBlank() {
        var p = open();
        assertThrows(IllegalArgumentException.class, () -> p.createFolder("  "));
        p.createFolder("A");
        p.createFolder(" A ");
        assertEquals(2, p.folders().size());
        assertEquals("A", p.folders().get(1).name());
        assertNotEquals(p.folders().get(0).id(), p.folders().get(1).id());
        assertEquals(2, open().folders().size());
    }

    @Test
    void renamingAndDeletingFolders() {
        var p = open();
        String f = p.createFolder("A");
        p.renameFolder(f, " B ");
        assertEquals("B", open().folders().get(0).name());
        assertThrows(IllegalArgumentException.class, () -> p.renameFolder(f, ""));
    }

    @Test
    void deletingAFolderKeepsTheChatsPrefs() {
        var p = open();
        p.choose(K, Flag.FAVORITE, true);
        String f = p.createFolder("A");
        p.setInFolder(f, K, true);
        Effective before = p.current(K);
        p.deleteFolder(f);
        assertEquals(before, p.current(K));
        assertTrue(p.folders().isEmpty());
    }

    @Test
    void damagedFileIsSetAsideNotOverwritten() throws IOException {
        Files.writeString(prefs(), "{roto");
        var p = open();
        assertNotNull(p.setAside());
        assertEquals("{roto", Files.readString(dir.resolve(p.setAside())));
        assertTrue(p.folders().isEmpty());
        p.choose(K, Flag.MUTED, true);
        assertTrue(read().path("chats").path(K).path("muted").asBoolean());
    }

    @Test
    void unknownVersionIsSetAside() throws IOException {
        Files.writeString(prefs(), "{\"version\":99}");
        var p = open();
        assertNotNull(p.setAside());
        assertEquals("{\"version\":99}", Files.readString(dir.resolve(p.setAside())));
        p.choose(K, Flag.MUTED, true);
        assertEquals(1, read().path("version").asInt());
    }

    @Test
    void aFailedWriteKeepsTheOldFileAndMemory() throws IOException {
        var p = open();
        p.choose(K, Flag.MUTED, true);
        p.flush();
        String before = Files.readString(prefs());
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeTrue(!Files.isWritable(dir));
            assertDoesNotThrow(() -> p.choose(K, Flag.FAVORITE, true));
            assertDoesNotThrow(p::flush);
            assertTrue(p.current(K).favorite());
            assertEquals(before, Files.readString(prefs()));
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void flushWritesOnlyWhenSomethingChanged() throws IOException {
        var p = open();
        p.reconcile(K, on(Flag.ARCHIVED));
        p.flush();
        FileTime old = FileTime.fromMillis(1_000_000_000L);
        Files.setLastModifiedTime(prefs(), old);
        p.flush();
        p.flush();
        assertEquals(old, Files.getLastModifiedTime(prefs()));
    }

    @Test
    void aPreviewPrefersTheFileAndChangesNothing() throws IOException {
        var p = open();
        p.choose(K, Flag.PINNED, false);
        p.chooseReminder(K, null);
        p.flush();
        String before = Files.readString(prefs());
        Instant at = Instant.parse("2026-11-07T14:00:00Z");
        var cached = new ServerValues(true, null, null, null, true, at, true);

        var shown = p.preview(K, cached);
        assertTrue(shown.archived(), "the file doesn't have it");
        assertFalse(shown.muted(), "nothing heard");
        assertFalse(shown.pinned(), "the file does");
        assertNull(shown.reminder(), "a dismissed reminder stays dismissed");
        assertEquals(at, p.preview("telegram:2", cached).reminder());

        p.flush();
        assertEquals(before, Files.readString(prefs()));
        assertFalse(p.current(K).archived());
        assertFalse(Files.exists(seen()) && Files.readString(seen()).contains("archived"), "no seen memory");
    }

    @Test
    void aBatchWritesOnceAtTheEnd() throws IOException {
        var p = open();
        p.batch(() -> {
            p.choose(K, Flag.ARCHIVED, true);
            p.batch(() -> p.choose(K, Flag.MUTED, true));
            p.rekey(K, "telegram:2");
            assertFalse(Files.exists(prefs()));
        });
        p.flush();
        assertEquals(1, p.mainWrites());
        JsonNode e = read().path("chats").path("telegram:2");
        assertTrue(e.path("archived").asBoolean());
        assertTrue(e.path("muted").asBoolean());
        p.choose(K, Flag.PINNED, true);
        p.flush();
        assertTrue(read().path("chats").path(K).path("pinned").asBoolean(), "writes each change again");
    }

    @Test
    void anUnmovableDamagedFileIsNeverOverwritten() throws IOException {
        Files.writeString(prefs(), "{roto");
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        PreferencesFile p;
        try {
            assumeTrue(!Files.isWritable(dir));
            p = open();
            assertTrue(p.unreadable());
            assertNull(p.setAside());
            p.choose(K, Flag.MUTED, true);
            p.flush();
            assertEquals("{roto", Files.readString(prefs()));
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        p.choose(K, Flag.FAVORITE, true);
        p.flush();
        assertFalse(p.unreadable());
        assertEquals("{roto", Files.readString(dir.resolve(p.setAside())));
        JsonNode e = read().path("chats").path(K);
        assertTrue(e.path("muted").asBoolean());
        assertTrue(e.path("favorite").asBoolean());
    }

    @Test
    void followDoesNotOverwriteAChoice() {
        var p = PreferencesFile.inMemory();
        p.choose(K, Flag.ARCHIVED, false);
        p.follow(K, Flag.ARCHIVED, true);
        assertFalse(p.current(K).archived());
    }

    @Test
    void rekeyKeepsThePersonsChoiceOverACopiedServerValue() {
        var p = PreferencesFile.inMemory();
        p.reconcile("telegram:2", on(Flag.ARCHIVED));
        p.choose("matrix:!b", Flag.ARCHIVED, false);
        p.rekey("matrix:!b", "telegram:2");
        assertFalse(p.current("telegram:2").archived());
        assertFalse(p.reconcile("telegram:2", on(Flag.ARCHIVED)).archived());
    }

    @Test
    void rekeyKeepsADismissedReminder() {
        var p = PreferencesFile.inMemory();
        p.reconcile("telegram:2", reminder(Instant.parse("2026-11-07T14:00:00Z")));
        p.chooseReminder("matrix:!b", null);
        p.rekey("matrix:!b", "telegram:2");
        assertNull(p.current("telegram:2").reminder());
        assertNull(p.reconcile("telegram:2", reminder(Instant.parse("2026-11-07T14:00:00Z"))).reminder());
    }

    @Test
    void anotherServerForgetsWhatTheOldOneSaid() throws IOException {
        var p = open();
        p.useServer("https://a");
        p.reconcile(K, on(Flag.ARCHIVED));
        p.flush();
        Path copy = Files.createDirectory(dir.resolve("copy"));
        Files.copy(prefs(), copy.resolve("preferences.json"));
        Files.copy(seen(), copy.resolve("server-seen.json"));

        var same = open();
        same.useServer("https://a");
        assertFalse(same.reconcile(K, off()).archived());

        var other = new PreferencesFile(copy.resolve("preferences.json"), copy.resolve("server-seen.json"));
        other.useServer("https://b");
        assertTrue(other.reconcile(K, off()).archived());
    }

    @Test
    void wrongShapesAreSetAside() throws IOException {
        for (String bad : List.of("{\"version\":1,\"chats\":[1]}", "{\"version\":1,\"folders\":{\"a\":1}}",
                "{\"version\":1,\"chats\":{\"k\":5}}", "{\"version\":1,\"folders\":[{\"name\":\"x\"}]}")) {
            Files.deleteIfExists(prefs());
            Files.writeString(prefs(), bad);
            var p = open();
            assertNotNull(p.setAside(), bad);
            assertEquals(bad, Files.readString(dir.resolve(p.setAside())));
            assertTrue(p.folders().isEmpty());
        }
    }

    @Test
    void aChangeLeavesTheDiskToTheWriter() throws IOException {
        var p = open();
        synchronized (p) {
            p.choose(K, Flag.MUTED, true);
            assertTrue(p.current(K).muted());
            assertFalse(Files.exists(prefs()), "nothing is written while the caller still holds the lock");
        }
        p.flush();
        assertTrue(read().path("chats").path(K).path("muted").asBoolean());
    }

    @Test
    void aBurstWritesFewerTimesThanChangesAndEndsWithTheLastState() throws IOException {
        var p = open();
        int changes = 1000;
        for (int i = 0; i < changes; i++) p.choose("telegram:" + i, Flag.MUTED, i % 2 == 0);
        p.choose(K, Flag.ARCHIVED, true);
        p.flush();
        assertTrue(p.mainWrites() < changes, p.mainWrites() + " writes");
        assertTrue(p.mainWrites() >= 1);
        JsonNode chats = read().path("chats");
        assertFalse(chats.path("telegram:999").path("muted").asBoolean());
        assertTrue(chats.path(K).path("archived").asBoolean());
        assertTrue(open().current(K).archived());
    }

    @Test
    void changesWhileTheLockIsHeldShareOneWrite() throws IOException {
        var p = open();
        synchronized (p) {
            for (int i = 0; i < 50; i++) p.choose("telegram:" + i, Flag.FAVORITE, true);
        }
        p.flush();
        assertEquals(1, p.mainWrites());
        assertEquals(50, read().path("chats").size());
    }

    @Test
    void flushAfterAFlushWritesNothingAndWakesNobody() {
        var p = open();
        p.choose(K, Flag.MUTED, true);
        p.flush();
        int writes = p.mainWrites();
        p.flush();
        assertEquals(writes, p.mainWrites());
    }

    @Test
    void anInterruptDoesNotLoseTheLastChange() throws IOException {
        var p = open();
        p.choose(K, Flag.MUTED, true);
        Thread.currentThread().interrupt();
        p.flush();
        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
        assertTrue(read().path("chats").path(K).path("muted").asBoolean());
    }

    @Test
    void inMemoryHasNothingToWait() {
        var p = PreferencesFile.inMemory();
        p.choose(K, Flag.MUTED, true);
        assertDoesNotThrow(p::flush);
        assertTrue(p.current(K).muted());
    }

    @Test
    void chatKeys() {
        assertEquals("telegram:1", ChatKeys.of("telegram", "1", "!r"));
        assertEquals("matrix:!r", ChatKeys.of(null, "1", "!r"));
        assertEquals("matrix:!r", ChatKeys.of("telegram", null, "!r"));
    }
}

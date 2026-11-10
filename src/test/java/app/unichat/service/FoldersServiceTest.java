package app.unichat.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoldersServiceTest {
    @TempDir Path dir;

    private final List<MockChatService> opened = new ArrayList<>();

    private MockChatService service() {
        MockChatService svc = new MockChatService(new PreferencesFile(dir.resolve("preferences.json"), dir.resolve("server-seen.json")));
        opened.add(svc);
        return svc;
    }

    @AfterEach
    void stopAll() throws Exception {
        opened.forEach(MockChatService::stop);
    }

    @Test
    void foldersNotifyAndHoldChatKeys() throws Exception {
        MockChatService svc = service();
        AtomicInteger changes = new AtomicInteger();
        svc.addListener(new ChatService.Listener() {
            @Override public void chatsChanged() { changes.incrementAndGet(); }
        });
        String id = svc.createFolder("Trabajo");
        assertEquals(1, changes.get());
        svc.setInFolder(id, svc.chat("!mama:demo"), true);
        assertEquals(2, changes.get());
        assertEquals(List.of("matrix:!mama:demo"), svc.folders().get(0).chatKeys());
        svc.renameFolder(id, "Casa");
        assertEquals("Casa", svc.folders().get(0).name());
        svc.deleteFolder(id);
        assertTrue(svc.folders().isEmpty());
    }

    @Test
    void foldersSurviveRecreatingTheService() throws Exception {
        MockChatService a = service();
        String id = a.createFolder("Trabajo");
        a.setInFolder(id, a.chat("!mama:demo"), true);
        a.stop();
        MockChatService b = service();
        assertEquals("Trabajo", b.folders().get(0).name());
        assertEquals(List.of("matrix:!mama:demo"), b.folders().get(0).chatKeys());
    }

    @Test
    void demoChoicesAreKeptAndEmptyFileKeepsDemoValues() throws Exception {
        MockChatService a = service();
        boolean before = a.chat("!mama:demo").favorite();
        a.setFavorite("!mama:demo", !before).get();
        a.setMuted("!andrea:demo", true).get();
        a.stop();
        MockChatService b = service();
        assertEquals(!before, b.chat("!mama:demo").favorite());
        assertTrue(b.chat("!andrea:demo").archived() && b.chat("!andrea:demo").muted(), "quiet records both flags");
        // untouched chats keep what the demo gave them
        assertEquals(new MockChatService().chat("!andres:demo").favorite(), b.chat("!andres:demo").favorite());
        assertEquals(new MockChatService().chat("!familia:demo").favorite(), b.chat("!familia:demo").favorite());
    }

    @Test
    void mutedServerSpaceSurvivesRecreatingTheService() throws Exception {
        MockChatService a = service();
        a.setMuted("!servidor:demo", true).get();
        assertNotNull(a.chat("!servidor:demo").prefKey());
        a.stop();
        MockChatService b = service();
        assertTrue(b.chat("!servidor:demo").muted() && b.chat("!servidor:demo").archived());
    }

    @Test
    void loggingOutSavesTheLastChange() throws Exception {
        MockChatService a = service();
        a.createFolder("Trabajo");
        a.logout().get();
        assertEquals("Trabajo", new PreferencesFile(dir.resolve("preferences.json"), dir.resolve("server-seen.json")).folders().get(0).name());
    }

    @Test
    void noWarningWhenFileIsFine() throws Exception {
        assertNull(service().preferencesWarning());
    }

    @Test
    void damagedFileIsSetAsideAndWarned() throws Exception {
        Files.writeString(dir.resolve("preferences.json"), "{ not json");
        MockChatService svc = service();
        String warning = svc.preferencesWarning();
        assertNotNull(warning);
        assertTrue(warning.contains("preferences.json.bad-"), warning);
        assertTrue(warning.contains("No se pudo leer"), warning);
    }

    @Test
    void unreadableThatCannotBeMovedWarnsNothingWillBeSaved() throws Exception {
        // A bad file in a read-only folder can't be moved aside.
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Files.writeString(locked.resolve("preferences.json"), "{ not json");
        assertTrue(locked.toFile().setWritable(false));
        try {
            PreferencesFile file = new PreferencesFile(locked.resolve("preferences.json"), locked.resolve("seen.json"));
            if (!file.unreadable()) return; // running as a user that ignores permissions
            String warning = new FolderActions(file, () -> { }).preferencesWarning();
            assertNotNull(warning);
            assertTrue(warning.contains("no se guardará"), warning);
            assertFalse(warning.contains("Se guardó aparte"), warning);
        } finally {
            locked.toFile().setWritable(true);
        }
    }
}

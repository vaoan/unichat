package app.unichat.autosave;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.MockChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSaveTest {
    @TempDir
    Path dir;
    private MockChatService svc;
    private final List<String> links = new CopyOnWriteArrayList<>();
    private AutoSave save;
    private Chat chat;

    @BeforeEach
    void start() throws Exception {
        svc = new MockChatService();
        chat = svc.chat("!familia:demo");
        Path cached = Files.writeString(dir.resolve("cached.bin"), "contenido");
        save = new AutoSave(svc, dir.resolve("out"), new AutoSave.Saver() {
            @Override
            public Path media(String mediaUrl) {
                return cached;
            }

            @Override
            public boolean link(String url, Path folder) {
                links.add(url + " -> " + folder.getFileName());
                return true;
            }
        });
    }

    @AfterEach
    void stop() {
        svc.stop();
    }

    private static Message media(MessageType type, String name, boolean mine) {
        Message m = new Message(name + type, "!familia:demo", mine ? "@me" : "@ana", "Ana", mine, Instant.parse("2026-11-10T15:04:05Z"));
        m.setType(type);
        m.setMediaUrl("mxc://s/" + name);
        m.setFileName(name);
        return m;
    }

    private void receiveAndWait(Message... ms) throws InterruptedException {
        for (Message m : ms) save.received(chat, m);
        CountDownLatch done = new CountDownLatch(1);
        save.afterPending(done::countDown);
        done.await();
    }

    private List<String> saved() throws Exception {
        Path folder = save.folder(chat);
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> s = Files.list(folder)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void nothingUnlessTheChatHasItOn() throws Exception {
        receiveAndWait(media(MessageType.IMAGE, "foto.jpg", false));
        assertEquals(List.of(), saved());
    }

    @Test
    void everythingOthersSendButStickersNorWhatIsMine() throws Exception {
        svc.setAutoSave(chat.id(), true).get();
        receiveAndWait(media(MessageType.IMAGE, "foto.jpg", false), media(MessageType.VIDEO, "video.mp4", false),
                media(MessageType.AUDIO, "nota.ogg", false), media(MessageType.FILE, "factura.pdf", false),
                media(MessageType.STICKER, "gato.webp", false), media(MessageType.IMAGE, "mia.jpg", true));
        assertEquals(List.of("factura.pdf", "foto.jpg", "nota.ogg", "video.mp4"), saved());
        assertEquals("contenido", Files.readString(save.folder(chat).resolve("foto.jpg")));
    }

    @Test
    void aNameAlreadyThereGetsANumberNothingIsOverwritten() throws Exception {
        svc.setAutoSave(chat.id(), true).get();
        receiveAndWait(media(MessageType.IMAGE, "foto.jpg", false), media(MessageType.IMAGE, "foto.jpg", false));
        assertEquals(List.of("foto (2).jpg", "foto.jpg"), saved());
    }

    @Test
    void theVideoBehindEachLinkGoesToTheChatsFolder() throws Exception {
        svc.setAutoSave(chat.id(), true).get();
        Message text = new Message("t1", chat.id(), "@ana", "Ana", false, Instant.now());
        text.setType(MessageType.TEXT);
        text.setBody("mira https://youtu.be/abc123 y https://www.instagram.com/reel/XYZ/.");
        receiveAndWait(text);
        String folder = save.folder(chat).getFileName().toString();
        assertEquals(List.of("https://youtu.be/abc123 -> " + folder, "https://www.instagram.com/reel/XYZ/ -> " + folder), links);
        assertTrue(folder.startsWith("Familia"), folder);
    }

    @Test
    void theSettingIsKeptInThePreferencesFile() throws Exception {
        svc.setAutoSave(chat.id(), true).get();
        assertTrue(svc.autoSaves(chat.id()));
        svc.setAutoSave(chat.id(), false).get();
        assertTrue(!svc.autoSaves(chat.id()));
    }
}

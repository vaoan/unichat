package app.unichat.autosave;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.SaveKind;
import app.unichat.service.ChatReader;
import app.unichat.service.MockChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextKeepingTest {
    @TempDir
    Path dir;
    private MockChatService svc;
    private Chat chat;
    private final List<Message> timeline = new CopyOnWriteArrayList<>();
    private final List<String> failed = new CopyOnWriteArrayList<>();
    private TextKeeping keeping;

    private ChatReader reader() {
        return new ChatReader() {
            @Override public List<Chat> chats() { return List.of(chat); }
            @Override public Chat chat(String id) { return id.equals(chat.id()) ? chat : null; }
            @Override public List<Message> messages(String id) { return new ArrayList<>(timeline); }
            @Override public CompletableFuture<Boolean> loadOlder(String id) { return CompletableFuture.completedFuture(false); }
            @Override public CompletableFuture<Message> message(String c, String m) { return CompletableFuture.completedFuture(null); }
        };
    }

    @BeforeEach
    void start() {
        svc = new MockChatService();
        chat = svc.chat("!familia:demo");
        SaveFolders.setOwn(chat.id(), dir.resolve("out"));
        keeping = new TextKeeping(svc, reader(), new TextLog(dir.resolve("texts"), Instant::now), Duration.ZERO, Duration.ZERO,
                new AutoSave.Progress() {
                    @Override public void changed(int done, int total) { }
                    @Override public void finished(int saved, Chat c) { }
                    @Override public void failed(String what, Chat c, String messageId, String why) { failed.add(what + ": " + why); }
                });
    }

    @AfterEach
    void stop() {
        SaveFolders.setOwn(chat.id(), null);
        svc.stop();
    }

    private Message said(String id, String body) {
        Message m = new Message(id, chat.id(), "@ana:demo", "Ana", false, Instant.now());
        m.setType(MessageType.TEXT);
        m.setBody(body);
        return m;
    }

    private void settled() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        keeping.afterPending(done::countDown);
        done.await();
    }

    private String copy() throws Exception {
        return Files.readString(dir.resolve("out").resolve(TextCopy.name(chat)), StandardCharsets.UTF_8);
    }

    @Test
    void whatWasSaidIsTakenTheMomentItArrivesSoItsDeletionLaterDoesntLoseIt() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.of(SaveKind.TEXTS)).get();
        CountDownLatch busy = new CountDownLatch(1);
        keeping.afterPending(() -> {
            try {
                busy.await(); // the thread busy with something else as the message arrives
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Message m = said("$1", "mañana no puedo");
        timeline.add(m);
        keeping.changed(chat.id());
        m.markDeleted(); // deleted before the thread got to it
        keeping.changed(chat.id());
        busy.countDown();
        settled();
        assertEquals(List.of("mañana no puedo"), keeping.said(m).get().stream().map(SavedText::text).toList());
        assertTrue(copy().contains("  mañana no puedo\n"), copy());
        assertEquals(List.of(), keeping.said(said("$2", "")).get(), "never seen: no copy");
    }

    @Test
    void onlyAChatThatKeepsItsTextsKeepsThem() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.of(SaveKind.PHOTOS)).get();
        Message m = said("$1", "hola");
        timeline.add(m);
        keeping.changed(chat.id());
        settled();
        assertEquals(List.of(), keeping.said(m).get());
        assertFalse(Files.exists(dir.resolve("texts")), "nothing written");
    }

    @Test
    void whatIWriteGoesUpAndLookingAsksTheCloudFirst() throws Exception {
        svc.setAutoSave(chat.id(), false, EnumSet.of(SaveKind.TEXTS)).get();
        AtomicInteger written = new AtomicInteger(), fetched = new AtomicInteger();
        keeping.setSync(written::incrementAndGet, () -> {
            fetched.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        Message m = said("$1", "hola");
        timeline.add(m);
        keeping.changed(chat.id());
        timeline.add(said("$2", "¿cómo vas?"));
        keeping.changed(chat.id());
        settled();
        assertEquals(1, written.get(), "once for both: a busy chat doesn't send its month's file at every message");
        keeping.said(m).get();
        assertEquals(1, fetched.get());
    }

    @Test
    void aCloudThatNeverAnswersDoesntKeepTheCopyHereFromShowing() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.of(SaveKind.TEXTS)).get();
        keeping.setSync(() -> { }, CompletableFuture::new); // asked, and never answering
        Message m = said("$1", "hola");
        timeline.add(m);
        keeping.changed(chat.id());
        assertEquals(List.of("hola"), keeping.said(m).get().stream().map(SavedText::text).toList(), "after the wait, what's here");
    }

    @Test
    void aCloudThatFailsShowsWhatsHere() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.of(SaveKind.TEXTS)).get();
        keeping.setSync(() -> { }, () -> CompletableFuture.failedFuture(new java.io.IOException("sin red")));
        Message m = said("$1", "hola");
        timeline.add(m);
        keeping.changed(chat.id());
        assertEquals(List.of("hola"), keeping.said(m).get().stream().map(SavedText::text).toList());
    }

    @Test
    void theCopyOfWhatChatsSaidIsReadableByItsOwnerAlone() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.of(SaveKind.TEXTS)).get();
        timeline.add(said("$1", "hola"));
        keeping.changed(chat.id());
        settled();
        Path texts = dir.resolve("texts");
        if (texts.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(texts)));
        }
    }

    @Test
    void whatAnotherComputerKeptReachesTheCopyWhenItArrives() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.of(SaveKind.TEXTS)).get();
        Path other = Files.createDirectories(dir.resolve("texts/0b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44"));
        Files.writeString(other.resolve(TextLog.key(chat.id()) + " 2026-11.jsonl"),
                SavedText.of(said("$0", "lo que vio la laptop"), Instant.now()).json() + "\n", StandardCharsets.UTF_8);
        keeping.arrived(Set.of(TextLog.key(chat.id())));
        settled();
        assertTrue(copy().contains("  lo que vio la laptop\n"), "made again with it");
        assertEquals(List.of(), failed);
    }
}

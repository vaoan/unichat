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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSaveTest {
    @TempDir
    Path dir;
    private MockChatService svc;
    private Chat chat;
    private final List<Message> timeline = new CopyOnWriteArrayList<>();
    /** The history the server would hand out page by page when scrolling back, newest page first. */
    private final java.util.Deque<List<Message>> older = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final List<String> links = new CopyOnWriteArrayList<>();
    private final List<String> told = new CopyOnWriteArrayList<>();
    /** A link that has no video, and a file that can't be downloaded. */
    private static final String NO_VIDEO = "https://example.org/sin-video", BROKEN = "mxc://s/roto.jpg", ENDLESS = "mxc://s/enorme.mov";
    /** Counted down when a download of {@link #ENDLESS} starts: it never ends by itself (a huge video). */
    private final CountDownLatch endlessStarted = new CountDownLatch(1);
    private AutoSave save;

    /** The chat's messages as the service holds them; nobody opens the chat. */
    private ChatReader reader() {
        return new ChatReader() {
            @Override public List<Chat> chats() { return List.of(chat); }
            @Override public Chat chat(String id) { return id.equals(chat.id()) ? chat : null; }
            @Override public List<Message> messages(String id) { return new ArrayList<>(timeline); }
            @Override public CompletableFuture<Boolean> loadOlder(String id) {
                List<Message> page = older.poll();
                if (page == null) return CompletableFuture.completedFuture(false);
                timeline.addAll(0, page);
                return CompletableFuture.completedFuture(true);
            }
            @Override public CompletableFuture<Message> message(String c, String m) { return CompletableFuture.completedFuture(null); }
        };
    }

    @BeforeEach
    void start() throws Exception {
        svc = new MockChatService();
        chat = svc.chat("!familia:demo");
        SaveFolders.setOwn(chat.id(), dir.resolve("out"));
        save = create();
    }

    private AutoSave create() throws Exception {
        Path cached = Files.writeString(dir.resolve("cached.bin"), "contenido");
        AutoSave a = new AutoSave(svc, reader(), new SavedUpTo(dir.resolve("autosave.json")), new AutoSave.Saver() {
            @Override public Path media(String mediaUrl, java.util.function.DoubleConsumer progress) throws java.io.IOException, InterruptedException {
                if (mediaUrl.endsWith("/grande.mov")) {
                    progress.accept(0.25);
                    progress.accept(0.5);
                }
                if (mediaUrl.equals(BROKEN)) throw new java.io.IOException("el puente no respondió");
                if (mediaUrl.equals(ENDLESS)) {
                    endlessStarted.countDown();
                    new CountDownLatch(1).await(); // until interrupted
                }
                return cached;
            }

            @Override public String link(String url, Path folder, String tag, java.util.function.DoubleConsumer progress) {
                if (url.equals(NO_VIDEO)) return "Unsupported URL";
                links.add(url);
                return null;
            }
        });
        a.setProgress(new AutoSave.Progress() {
            @Override public void changed(int done, int total) { told.add("progress " + done + "/" + total); }
            @Override public void finished(int saved, Chat c) { told.add("finished " + saved + " " + c.id()); }
            @Override public void failed(String what, Chat c, String why) { told.add("failed " + what + ": " + why); }
        });
        return a;
    }

    @AfterEach
    void stop() {
        SaveFolders.setOwn(chat.id(), null);
        svc.stop();
    }

    @Test
    void aChatsOwnFolderChangedTakesWhatItSaved() throws Exception {
        Path was = dir.resolve("out");
        Files.createDirectories(was);
        Files.writeString(was.resolve("foto.jpg"), "f");
        assertEquals(1, save.setOwn(chat, dir.resolve("elsewhere")).get());
        assertTrue(Files.exists(dir.resolve("elsewhere/foto.jpg")), "it's in the new folder");
        assertFalse(Files.exists(was), "not left behind");
    }

    @Test
    void aNewBaseFolderTakesEachChatsFolderButNothingElse() throws Exception {
        String forced = System.getProperty("unichat.autosaveDir");
        System.clearProperty("unichat.autosaveDir");
        try {
            SaveFolders.setOwn(chat.id(), null);
            Path oldBase = dir.resolve("Downloads/Unichat");
            SaveFolders.setBase(oldBase);
            Path chatFolder = Files.createDirectories(oldBase.resolve(FileNames.folder(chat.title())));
            Files.writeString(chatFolder.resolve("nota.ogg"), "n");
            Files.writeString(oldBase.resolve("mio.txt"), "the person's own");
            Path newBase = dir.resolve("Documents/Unichat");
            assertEquals(1, save.setBase(newBase).get());
            assertTrue(Files.exists(newBase.resolve(FileNames.folder(chat.title())).resolve("nota.ogg")));
            assertTrue(Files.exists(oldBase.resolve("mio.txt")), "what isn't a chat's stays where it was");
        } finally {
            SaveFolders.setBase(null);
            if (forced != null) System.setProperty("unichat.autosaveDir", forced);
        }
    }

    private static Message media(MessageType type, String name, boolean mine, Instant at) {
        Message m = new Message(name + type + at, "!familia:demo", mine ? "@me" : "@ana", "Ana", mine, at);
        m.setType(type);
        m.setMediaUrl("mxc://s/" + name);
        m.setFileName(name);
        return m;
    }

    /** Messages arrive (a sync), and saving has done what that set off. */
    private void arrive(Message... ms) throws InterruptedException {
        timeline.addAll(List.of(ms));
        save.messagesChanged(chat.id());
        CountDownLatch done = new CountDownLatch(1);
        save.afterPending(done::countDown);
        done.await();
    }

    private List<String> saved() throws Exception {
        Path folder = save.folder(chat);
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> s = Files.list(folder)) {
            // The names carry the message's id ("hoy [id].jpg"): what's compared here is the name it had.
            return s.map(p -> p.getFileName().toString()).filter(n -> !n.startsWith("."))
                    .map(n -> n.replaceFirst(" \\[[^\\]]+\\](\\.[^.]+)?$", "$1")).sorted().toList();
        }
    }

    private static Instant later(int s) {
        return Instant.now().plusSeconds(s);
    }

    @Test
    void nothingUnlessTheChatHasItOn() throws Exception {
        arrive(media(MessageType.IMAGE, "foto.jpg", false, later(1)));
        assertEquals(List.of(), saved());
    }

    @Test
    void whatArrivesIsSavedWithoutOpeningTheChatButStickersNorWhatIsMine() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.allOf(SaveKind.class)).get();
        save.startNow(chat.id());
        arrive(media(MessageType.IMAGE, "foto.jpg", false, later(1)), media(MessageType.VIDEO, "video.mp4", false, later(2)),
                media(MessageType.AUDIO, "nota.ogg", false, later(3)), media(MessageType.FILE, "factura.pdf", false, later(4)),
                media(MessageType.STICKER, "gato.webp", false, later(5)), media(MessageType.IMAGE, "mia.jpg", true, later(6)));
        assertEquals(List.of("factura.pdf", "foto.jpg", "nota.ogg", "video.mp4"), saved());
    }

    @Test
    void onlyTheKindsChosen() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.of(SaveKind.DOCUMENTS)).get();
        save.startNow(chat.id());
        Message text = new Message("t", chat.id(), "@ana", "Ana", false, later(3));
        text.setType(MessageType.TEXT);
        text.setBody("mira https://youtu.be/abc");
        arrive(media(MessageType.IMAGE, "foto.jpg", false, later(1)), media(MessageType.FILE, "factura.pdf", false, later(2)), text);
        assertEquals(List.of("factura.pdf"), saved());
        assertEquals(List.of(), links, "link videos weren't chosen");
    }

    @Test
    void whatWasThereBeforeTurningItOnStaysOutAndNothingTwice() throws Exception {
        timeline.add(media(MessageType.IMAGE, "vieja.jpg", false, Instant.now().minusSeconds(3600)));
        svc.setAutoSave(chat.id(), EnumSet.allOf(SaveKind.class)).get();
        save.startNow(chat.id());
        arrive(media(MessageType.IMAGE, "nueva.jpg", false, later(1)));
        arrive(); // another sync with nothing new
        assertEquals(List.of("nueva.jpg"), saved());
    }

    @Test
    void whatArrivedWhileClosedIsSavedAtTheNextStart() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.allOf(SaveKind.class)).get();
        save.startNow(chat.id());
        arrive(media(MessageType.IMAGE, "antes.jpg", false, later(1)));
        save = create(); // Unichat closed and opened again: where it got to is on disk
        arrive(media(MessageType.IMAGE, "mientras.jpg", false, later(2)));
        assertEquals(List.of("antes.jpg", "mientras.jpg"), saved());
    }

    @Test
    void theVideoBehindEachLinkWhenChosen() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.of(SaveKind.LINKS)).get();
        save.startNow(chat.id());
        Message text = new Message("t1", chat.id(), "@ana", "Ana", false, later(1));
        text.setType(MessageType.TEXT);
        text.setBody("mira https://youtu.be/abc123 y https://www.instagram.com/reel/XYZ/.");
        arrive(text);
        assertEquals(List.of("https://youtu.be/abc123", "https://www.instagram.com/reel/XYZ/"), links);
    }

    @Test
    void aRoundSaysHowItGoesAndHowManyAtTheEnd() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.allOf(SaveKind.class)).get();
        save.startNow(chat.id());
        arrive(media(MessageType.IMAGE, "a.jpg", false, later(1)), media(MessageType.IMAGE, "b.jpg", false, later(2)));
        assertEquals(List.of("progress 0/2", "progress 1/2", "progress 2/2", "finished 2 " + chat.id(), "progress 0/0"), told);
    }

    @Test
    void whatFailsIsToldWithWhatAndWhyAndIsntCountedAsSaved() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.allOf(SaveKind.class)).get();
        save.startNow(chat.id());
        Message broken = media(MessageType.IMAGE, "roto.jpg", false, later(1));
        broken.setMediaUrl(BROKEN);
        Message text = new Message("t", chat.id(), "@ana", "Ana", false, later(2));
        text.setType(MessageType.TEXT);
        text.setBody(NO_VIDEO);
        arrive(broken, text, media(MessageType.IMAGE, "bien.jpg", false, later(3)));
        assertTrue(told.contains("failed roto.jpg: el puente no respondió"), told.toString());
        assertTrue(told.contains("failed " + NO_VIDEO + ": Unsupported URL"), told.toString());
        assertTrue(told.contains("finished 1 " + chat.id()), "only the one saved counts: " + told);
        assertEquals(List.of("bien.jpg"), saved());
    }

    @Test
    void noPermissionIsSaidPlainly() {
        String why = AutoSave.why(new java.nio.file.AccessDeniedException(dir.toString()), dir, null);
        assertTrue(why.contains("permiso") && why.contains(dir.toString()), why);
    }

    @Test
    void whatWasSavedStaysWhenTheMessageIsDeletedOrExpires() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.allOf(SaveKind.class)).get();
        save.startNow(chat.id());
        Message once = media(MessageType.IMAGE, "una-vez.jpg", false, later(1));
        arrive(once);
        once.setDeleted(true); // deleted by whoever sent it, or seen once and gone
        arrive();
        assertEquals(List.of("una-vez.jpg"), saved(), "the saved copy is an archive: never deleted with the message");
    }

    /** Exploring until it ends, however many pages that takes. */
    private void exploreAndWait() throws InterruptedException {
        CountDownLatch over = new CountDownLatch(1);
        Runnable l = () -> { if (!save.exploring(chat.id()).running()) over.countDown(); };
        save.addExploringListener(l);
        save.explore(chat.id());
        over.await();
        save.removeExploringListener(l);
        CountDownLatch done = new CountDownLatch(1);
        save.afterPending(done::countDown);
        done.await();
    }

    @Test
    void exploringGoesBackToTheBeginningSavingOnlyTheKindsChosenOnce() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.of(SaveKind.PHOTOS, SaveKind.DOCUMENTS)).get();
        Instant t = Instant.now();
        timeline.add(media(MessageType.IMAGE, "ayer.jpg", false, t.minusSeconds(86_400)));
        older.add(List.of(media(MessageType.FILE, "contrato.pdf", false, t.minusSeconds(5 * 86_400)),
                media(MessageType.VIDEO, "video.mp4", false, t.minusSeconds(4 * 86_400))));
        older.add(List.of(media(MessageType.IMAGE, "primera.jpg", false, t.minusSeconds(30 * 86_400))));
        save.startNow(chat.id());
        arrive(media(MessageType.IMAGE, "hoy.jpg", false, later(1))); // saved as it arrived
        exploreAndWait();
        assertEquals(List.of("ayer.jpg", "contrato.pdf", "hoy.jpg", "primera.jpg"), saved(), "no videos: not chosen; hoy.jpg once");
        AutoSave.Exploring e = save.exploring(chat.id());
        assertTrue(e.start(), "got to the beginning");
        assertEquals(3, e.saved());
        assertTrue(told.contains("finished 3 " + chat.id()), told.toString());
        exploreAndWait(); // again: it got to the beginning, so it goes through it all once more
        assertEquals(List.of("ayer.jpg", "contrato.pdf", "hoy.jpg", "primera.jpg"), saved(), "nothing twice");
        assertEquals(4, save.exploring(chat.id()).looked(), "it did look again (pressing it used to do nothing)");
        assertEquals(0, save.exploring(chat.id()).saved(), "and found it all saved already");
    }

    @Test
    void exploringSaysWhatItsDoingAtEachStep() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.of(SaveKind.PHOTOS, SaveKind.DOCUMENTS)).get();
        Instant t = Instant.now();
        timeline.add(media(MessageType.IMAGE, "ayer.jpg", false, t.minusSeconds(86_400)));
        older.add(List.of(media(MessageType.FILE, "contrato.pdf", false, t.minusSeconds(5 * 86_400)),
                media(MessageType.VIDEO, "video.mp4", false, t.minusSeconds(4 * 86_400))));
        save.startNow(chat.id());
        List<String> steps = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable l = () -> {
            AutoSave.Now now = save.exploring(chat.id()).now();
            if (now != null) steps.add(now.doing() + (now.what() == null ? "" : " " + now.what()));
        };
        save.addExploringListener(l);
        exploreAndWait();
        save.removeExploringListener(l);
        assertTrue(steps.contains("LOOKING"), steps.toString());
        assertTrue(steps.stream().anyMatch(x -> x.startsWith("DOWNLOADING") && x.contains("contrato")), steps.toString());
        assertTrue(steps.contains("ASKING_OLDER"), "asking the server for older messages: " + steps);
        AutoSave.Exploring e = save.exploring(chat.id());
        assertEquals(3, e.looked(), "ayer, contrato and the video looked at (the video not saved: not chosen)");
        assertEquals(null, e.now(), "nothing going on once over");
    }

    @Test
    void stoppingExploringStopsTheDownloadGoingOnAndSaysSoAtOnce() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.allOf(SaveKind.class)).get();
        Message huge = media(MessageType.VIDEO, "enorme.mov", false, Instant.now().minusSeconds(100));
        huge.setMediaUrl(ENDLESS);
        timeline.add(huge);
        save.startNow(chat.id());
        CountDownLatch over = new CountDownLatch(1);
        Runnable l = () -> { if (!save.exploring(chat.id()).running()) over.countDown(); };
        save.addExploringListener(l);
        save.explore(chat.id());
        endlessStarted.await();
        save.stopExploring(chat.id());
        AutoSave.Now now = save.exploring(chat.id()).now();
        assertEquals(AutoSave.Doing.STOPPING, now == null ? null : now.doing(), "the button's press shows at once");
        over.await(); // without stopping the download, this never comes
        save.removeExploringListener(l);
        assertEquals(0, save.exploring(chat.id()).saved(), "the cut download isn't counted as saved");
        assertEquals(0, save.exploring(chat.id()).unavailable(), "nor as gone");
        arrive(media(MessageType.IMAGE, "despues.jpg", false, later(1)));
        assertTrue(saved().stream().anyMatch(n -> n.startsWith("despues")), "and saving goes on as before: " + saved());
    }

    @Test
    void exploringSaysHowMuchOfTheFileHasArrived() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.allOf(SaveKind.class)).get();
        timeline.add(media(MessageType.VIDEO, "grande.mov", false, Instant.now().minusSeconds(100)));
        save.startNow(chat.id());
        List<Double> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable l = () -> {
            AutoSave.Now now = save.exploring(chat.id()).now();
            if (now != null && now.done() >= 0 && (seen.isEmpty() || seen.getLast() != now.done())) seen.add(now.done());
        };
        save.addExploringListener(l);
        exploreAndWait();
        save.removeExploringListener(l);
        assertEquals(List.of(0.25, 0.5), seen, "what the download told, as it told it");
    }

    @Test
    void aReelThatCameAsVideoIsntDownloadedAgainFromItsPage() {
        Message video = media(MessageType.VIDEO, "AQN8.mp4", false, Instant.now());
        video.setExternalUrl("https://www.instagram.com/reel/DfA9wPsOEt2/");
        assertTrue(AutoSave.links(video).isEmpty(), "the video is the reel: once is enough");
        Message picture = media(MessageType.IMAGE, "portada.jpg", false, Instant.now());
        picture.setExternalUrl("https://www.instagram.com/reel/DfA9wPsOEt2/");
        assertEquals(java.util.Set.of("https://www.instagram.com/reel/DfA9wPsOEt2/"), AutoSave.links(picture),
                "a reel shown as a picture still gets its video");
    }

    @Test
    void whatsGoneIsCountedAndToldOnceNotFileByFile() throws Exception {
        svc.setAutoSave(chat.id(), EnumSet.allOf(SaveKind.class)).get();
        Instant t = Instant.now();
        Message gone1 = media(MessageType.IMAGE, "vieja1.jpg", false, t.minusSeconds(100));
        gone1.setMediaUrl(BROKEN);
        Message gone2 = media(MessageType.IMAGE, "vieja2.jpg", false, t.minusSeconds(200));
        gone2.setMediaUrl(BROKEN);
        timeline.addAll(List.of(gone2, gone1));
        save.startNow(chat.id());
        exploreAndWait();
        assertEquals(2, save.exploring(chat.id()).unavailable());
        assertEquals(1, told.stream().filter(x -> x.startsWith("failed")).count(), "one notice: " + told);
        assertTrue(told.stream().anyMatch(x -> x.startsWith("failed 2 archivos anteriores ya no estaban")), told.toString());
    }
}

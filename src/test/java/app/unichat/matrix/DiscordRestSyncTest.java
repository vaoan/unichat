package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.service.ArchiveWaking.Step;
import app.unichat.service.PreferencesFile;
import app.unichat.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static app.unichat.matrix.RestServer.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole service with a homeserver: archived Discord rooms leave the sync at once, its long poll dropped so the next
 * request already leaves them out, and what they got while resting is there once the archive opens.
 */
class DiscordRestSyncTest {
    private RestServer server;
    private MatrixChatService svc;

    @BeforeEach
    void start() throws Exception {
        Settings.get().setNotifyArchived(false);
        server = new RestServer();
        server.initialSync = sync(server("!s:x", "111", "!c1:x", "!c2:x"), channel("!c1:x", "c1"), channel("!c2:x", "c2"),
                channel("!n:x", "n"));
        svc = new MatrixChatService(server.url(), ME, "tok", PreferencesFile.inMemory(), "device");
        java.nio.file.Files.deleteIfExists(svc.syncCacheFile()); // an earlier test's: this one starts from nothing
        svc.addListener(server.changes.listener());
        svc.addWakingListener(server.changes::changed);
        svc.start();
        server.changes.until("long polling", () -> server.log.stream().anyMatch(l -> l.endsWith("timeout=30000")));
    }

    @AfterEach
    void stop() {
        svc.stop();
        server.close();
        Settings.get().setNotifyArchived(false);
    }

    private Chat chat(String id) {
        return svc.chat(id);
    }

    /** The newest sync request the loop made, after the given point in the log. */
    private boolean syncSince(int from, String what) {
        return server.log.subList(from, server.log.size()).stream().anyMatch(l -> l.startsWith("sync " + what));
    }

    @Test
    void archivingMovesARoomInOrOutAtOnce() throws Exception {
        int before = server.log.size();
        svc.setArchived("!s:x", true).get();
        server.changes.until("a request leaves the server out", () -> server.notRoomsPosted().size() == 1
                && syncSince(before, "f"));
        assertEquals(new TreeSet<>(List.of("!s:x", "!c1:x", "!c2:x")), server.notRoomsPosted().getFirst());

        int resting = server.log.size();
        svc.setArchived("!c2:x", false).get();
        server.changes.until("the channel comes back with what it missed", () -> server.catchUps().equals(List.of(new TreeSet<>(List.of("!c2:x")))));
        assertEquals(new TreeSet<>(List.of("!s:x", "!c1:x")), server.notRoomsPosted().getLast());
        server.changes.until("and the next request has it", () -> syncSince(resting, "f"));
    }

    @Test
    void aMessageWhileRestingIsShownAfterWaking() throws Exception {
        svc.setArchived("!s:x", true).get();
        server.changes.until("resting", () -> server.notRoomsPosted().size() == 1);
        server.missed.put("!c1:x", List.of(message("$while", "@discord_1:example.org", "mientras dormía", 5000),
                message("$and", "@discord_2:example.org", "y otro", 6000)));
        server.unread.put("!c1:x", 2);

        int before = server.log.size();
        svc.setArchiveOpen(true);
        server.changes.until("woken", () -> svc.waking() != null && svc.waking().step() == Step.DONE);
        server.changes.until("the usual filter again, leaving nothing out", () -> syncSince(before, "inline since"));
        assertTrue(svc.messages("!c1:x").stream().anyMatch(m -> m.id().equals("$while")), "shown after waking");
        assertTrue(svc.messages("!c1:x").stream().anyMatch(m -> m.id().equals("$and")));
        assertEquals(2, chat("!c1:x").unreadCount(), "its unread count is the server's");
        assertTrue(chat("!c1:x").archived(), "still archived: only awake");
    }

    @Test
    void notifyArchivedOnWakesThemAtOnce() throws Exception {
        svc.setArchived("!s:x", true).get();
        server.changes.until("resting", () -> server.notRoomsPosted().size() == 1);
        Settings.get().setNotifyArchived(true);
        server.changes.until("woken", () -> svc.waking() != null && svc.waking().step() == Step.DONE);
        assertEquals(1, server.catchUps().size());
    }

    @Test
    void aCatchUpNeverErasesWhatCameAfterItAndNothingLiveComesBetween() throws Exception {
        svc.setArchived("!s:x", true).get();
        server.changes.until("resting", () -> server.notRoomsPosted().size() == 1 && syncSince(0, "f"));
        server.missed.put("!c1:x", List.of(message("$while", "@discord_1:example.org", "mientras dormía", 5000)));
        server.liveAfterCatchUp.put("!c1:x", List.of(message("$after", "@discord_2:example.org", "justo después", 9000)));
        svc.setArchiveOpen(true);
        server.changes.until("the live sync after it", () -> server.log.contains("live answered"));
        server.changes.until("shown", () -> svc.messages("!c1:x").stream().anyMatch(m -> m.id().equals("$after")));
        assertTrue(svc.messages("!c1:x").stream().anyMatch(m -> m.id().equals("$while")), "and what it missed");

        List<String> log = List.copyOf(server.log);
        int start = log.indexOf("catch-up [!c1:x, !c2:x, !s:x]");
        int end = log.indexOf("caught up");
        assertTrue(start >= 0 && log.subList(start, end).stream().noneMatch(l -> l.startsWith("sync ")), "nothing live meanwhile: " + log);
        String before = log.subList(0, start).reversed().stream().filter(l -> l.startsWith("sync ") && l.contains("since=")).findFirst().orElseThrow();
        String after = log.subList(end, log.size()).stream().filter(l -> l.startsWith("sync ")).findFirst().orElseThrow();
        assertEquals(since(before), since(after), "the request after it asks again from where the sync was: " + log);
        assertTrue(after.startsWith("sync inline"), "with the filter that woke them: " + after);
    }

    private static String since(String line) {
        return line.replaceAll(".*since=(\\S+).*", "$1");
    }

    @Test
    void aLiveMessageInAnotherChatDuringAWakeStillNotifies() throws Exception {
        List<String> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        svc.addListener(new app.unichat.service.ChatService.Listener() {
            @Override public void incomingMessage(Chat chat, app.unichat.model.Message m) { told.add(m.id()); server.changes.changed(); }
        });
        svc.setArchived("!s:x", true).get();
        server.changes.until("resting", () -> server.notRoomsPosted().size() == 1);
        server.liveAfterCatchUp.put("!n:x", List.of(message("$news", "@discord_2:example.org", "hola", System.currentTimeMillis())));
        svc.setArchiveOpen(true);
        server.changes.until("it notifies", () -> told.contains("$news"));
    }

    @Test
    void aFilterTheServerForgotIsPostedAgain() throws Exception {
        svc.setArchived("!s:x", true).get();
        server.changes.until("resting", () -> server.notRoomsPosted().size() == 1 && syncSince(0, "f"));
        String gone = server.filters.keySet().iterator().next();
        server.filters.clear(); // its database wiped
        svc.wake();
        server.changes.until("posted again", () -> server.notRoomsPosted().size() == 2);
        assertTrue(server.log.contains("unknown filter " + gone), server.log.toString());
        assertTrue(Settings.get().getSet(SyncFilter.KEPT_KEY).stream().noneMatch(e -> e.endsWith("=" + gone)), "forgotten here too");
    }

    @Test
    void droppingALongPollForANewFilterIsNoFailure() throws Exception {
        List<Long> pauses = new java.util.concurrent.CopyOnWriteArrayList<>();
        svc.pauseRetriesWith(pauses::add);
        int before = server.log.size();
        svc.setArchived("!s:x", true).get();
        server.changes.until("the next poll has the filter", () -> server.log.subList(before, server.log.size()).stream()
                .anyMatch(l -> l.startsWith("sync f") && l.endsWith("timeout=30000")));
        assertEquals(List.of(), pauses, "no backoff, no \"no connection\"");
    }

    @Test
    void aFilterForgottenAgainAtOnceGoesToTheBackoff() throws Exception {
        java.util.concurrent.CountDownLatch paused = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
        svc.pauseRetriesWith(ms -> {
            paused.countDown();
            never.await(); // the test ends here: what matters is how many tries came before it
        });
        server.forgetEveryFilter = true;
        svc.setArchived("!s:x", true).get();
        paused.await();
        long unknown = server.log.stream().filter(l -> l.startsWith("unknown filter")).count();
        assertTrue(unknown <= 2, "forgotten once and posted again, then the backoff: " + server.log);
        never.countDown();
    }
}

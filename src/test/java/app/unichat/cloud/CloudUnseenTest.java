package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.Seen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * While nobody can see Unichat, the cloud's media waits (the upload and the day's check), and the small parts go on:
 * the settings, what the AI learned, the texts and the vault. Seen again, a round sends what waited.
 */
class CloudUnseenTest {
    private static final String DESK = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44", TEXT = "3f2a9c1b2d4e 2026-11.jsonl";
    private static final String PHOTO = CloudAccount.MEDIA + "/Ana/a.jpg", SECOND = CloudAccount.MEDIA + "/Ana/b.jpg";

    @TempDir
    Path dir;
    private FakeDav account;
    private Map<String, String> portableWere, keysWere;
    private final List<CountDownLatch> held = new java.util.ArrayList<>();
    private VaultComputer desk;

    @BeforeEach
    void start() throws Exception {
        Seen.reset(true);
        account = new FakeDav("u", "p");
        portableWere = Settings.get().portable();
        keysWere = VaultComputer.kept();
        desk = new VaultComputer(account, dir, DESK).write("texts/here/" + TEXT, "{}\n"); // its settings stay in for the test
    }

    @AfterEach
    void stop() throws Exception {
        held.forEach(CountDownLatch::countDown); // nothing waits past the test
        desk.cloud.stop();
        desk.cloud.syncNow().get(); // whatever was queued is over before the account goes
        Seen.reset(true);
        account.close();
        Settings.get().replacePortable(portableWere, k -> false);
        keysWere.forEach(Settings.get()::set);
    }

    /** The next PUT of {@code path} held: the returned latch lets it go; {@code there} goes once it waits. */
    private CountDownLatch holdPut(String path, CountDownLatch there) {
        CountDownLatch latch = new CountDownLatch(1);
        held.add(latch);
        account.arrived.put(path, there);
        account.holdOnce.put(path, latch);
        return latch;
    }

    private List<String> mediaPuts() {
        return account.log.stream().filter(l -> l.startsWith("PUT " + CloudAccount.MEDIA + "/")).toList();
    }

    /** How many rounds began: each reads the vault's file once (no vault job runs in these tests). */
    private long rounds() {
        return account.log.stream().filter(("GET " + Vault.INFO)::equals).count();
    }

    /** Started as Unichat starts it, and its first round (the timer's, at once) over. */
    private void started() throws Exception {
        desk.cloud.start(Duration.ofDays(1));
        while (rounds() == 0) Thread.onSpinWait(); // the timer's round began; one asked now runs after it
        desk.cloud.syncNow().get();
    }

    @Test
    void unseenSyncsTheSmallPartsButNoMedia() throws Exception {
        Seen.reset(false);
        Cloud.Status plain = desk.cloud.syncNow().get();
        assertNull(plain.error());
        assertTrue(account.log.contains("PUT " + CloudAccount.SETTINGS_FILE), "the settings went: " + account.log);
        assertTrue(account.log.contains("PUT " + Cloud.Part.AI.path()), "what the AI learned went: " + account.log);
        assertTrue(account.log.contains("PUT " + TextSync.ROOT + "/" + DESK + "/" + TEXT), "the texts went: " + account.log);
        desk.cloud.vault().createVault().get();
        int before = account.log.size();
        desk.write("pinned-messages.json", "{\"!a:b\":[\"$1\"]}");
        assertNull(desk.cloud.syncNow().get().error());
        List<String> round = account.log.subList(before, account.log.size());
        assertTrue(round.stream().anyMatch(l -> l.startsWith("PUT " + Vault.ROOT + "/")), "the vault's parts went: " + round);
        assertEquals(List.of(), mediaPuts(), "no photo went up");
        assertTrue(account.files.keySet().stream().noneMatch(p -> p.startsWith(CloudAccount.MEDIA + "/")), "nothing of media there");
    }

    @Test
    void seenAgainSendsTheMedia() throws Exception {
        Seen.reset(false);
        started();
        assertEquals(List.of(), mediaPuts(), "nothing went up while unseen");
        long before = rounds();
        Seen.reset(true); // queues its round on the cloud's thread before it returns
        desk.cloud.syncNow().get(); // runs after it
        assertEquals(before + 2, rounds(), "the round on being seen, and the one asked after it");
        assertEquals(List.of("PUT " + PHOTO), mediaPuts());
        int lastRound = account.log.lastIndexOf("GET " + Vault.INFO);
        assertTrue(account.log.indexOf("PUT " + PHOTO) < lastRound, "the round on being seen sent it, not the one after: " + account.log);
    }

    @Test
    void hidingMidUploadStopsAfterTheCurrentFile() throws Exception {
        desk.write("Ana/b.jpg", "otra foto");
        CountDownLatch there = new CountDownLatch(1);
        CountDownLatch release = holdPut(PHOTO, there);
        CompletableFuture<Cloud.Status> round = desk.cloud.syncNow();
        there.await();
        Seen.reset(false);
        release.countDown();
        Cloud.Status s = round.get();
        assertNull(s.error());
        assertEquals(List.of("PUT " + PHOTO), mediaPuts(), "the file going finished, the next waited");
        assertTrue(account.files.containsKey(PHOTO));
        assertFalse(account.files.containsKey(SECOND));
        assertEquals(1, s.waiting(), "the second still waits");
        assertNull(Settings.get().getString("cloud.lastVerify", null), "the day's check, due, waits too");
        Seen.reset(true);
        assertNull(desk.cloud.syncNow().get().error());
        assertTrue(account.files.containsKey(SECOND), "seen again, it went");
    }

    @Test
    void flappingQueuesAtMostOneRound() throws Exception {
        started();
        desk.write("Ana/b.jpg", "otra foto");
        CountDownLatch there = new CountDownLatch(1);
        CountDownLatch release = holdPut(SECOND, there);
        long before = rounds();
        CompletableFuture<Cloud.Status> round = desk.cloud.syncNow();
        there.await();
        for (int i = 0; i < 5; i++) {
            Seen.reset(false);
            Seen.reset(true);
        }
        release.countDown();
        round.get();
        desk.cloud.syncNow().get(); // runs after whatever seeing it again queued
        assertEquals(before + 3, rounds(), "the held round, one queued by seeing it again, and the one asked after");
    }

    @Test
    void aVerifyDueWhileUnseenRunsWhenSeen() throws Exception {
        assertNull(desk.cloud.syncNow().get().error()); // the photo went up and the day's check ran
        Instant old = Instant.now().minus(Cloud.VERIFY_EVERY.multipliedBy(2));
        Settings.get().set("cloud.lastVerify", old.toString());
        account.put(PHOTO, "fo".getBytes(StandardCharsets.UTF_8)); // cut short there: the check sends it again
        Seen.reset(false);
        Cloud.Status unseen = desk.cloud.syncNow().get();
        assertEquals(old, unseen.lastVerified(), "due, and still due");
        assertEquals(old.toString(), Settings.get().getString("cloud.lastVerify", null));
        assertEquals("fo", new String(account.files.get(PHOTO), StandardCharsets.UTF_8), "nothing checked while unseen");
        Seen.reset(true);
        Cloud.Status seen = desk.cloud.syncNow().get();
        assertTrue(seen.lastVerified().isAfter(old), "the check ran once seen");
        assertEquals("foto", new String(account.files.get(PHOTO), StandardCharsets.UTF_8), "and repaired it");
    }

    @Test
    void aVerifyCutShortByHidingStaysDue() throws Exception {
        desk.write("Ana/b.jpg", "otra foto");
        assertNull(desk.cloud.syncNow().get().error());
        Instant old = Instant.now().minus(Cloud.VERIFY_EVERY.multipliedBy(2));
        Settings.get().set("cloud.lastVerify", old.toString());
        account.put(PHOTO, "fo".getBytes(StandardCharsets.UTF_8));
        account.put(SECOND, "otra".getBytes(StandardCharsets.UTF_8));
        CountDownLatch there = new CountDownLatch(1);
        CountDownLatch release = holdPut(PHOTO, there); // the check's repair of the first
        CompletableFuture<Cloud.Status> round = desk.cloud.syncNow();
        there.await();
        Seen.reset(false);
        release.countDown();
        assertEquals(old, round.get().lastVerified(), "cut short: still due");
        assertEquals("otra", new String(account.files.get(SECOND), StandardCharsets.UTF_8), "the next file waited");
        Seen.reset(true);
        assertTrue(desk.cloud.syncNow().get().lastVerified().isAfter(old));
        assertEquals("otra foto", new String(account.files.get(SECOND), StandardCharsets.UTF_8));
    }

    @Test
    void theStatusSaysMediaWaits() throws Exception {
        Seen.reset(false);
        assertTrue(desk.cloud.syncNow().get().mediaPaused(), "unseen: the media waited");
        assertTrue(desk.cloud.status().mediaPaused());
        Seen.reset(true);
        assertFalse(desk.cloud.syncNow().get().mediaPaused(), "seen: it went");
        CloudAccount.setMedia(false);
        Seen.reset(false);
        assertFalse(desk.cloud.syncNow().get().mediaPaused(), "media off: nothing waits for being seen");
    }
}

package app.unichat.cloud;

import app.unichat.settings.Settings;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ajustes → Nube → Bóveda's jobs and a round: never alongside the round's vault part, never waiting for its media (a
 * day's check of every file uploaded, or a long video, takes hours).
 */
class CloudVaultJobsTest {
    private static final String DESK = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44", TEXT = "3f2a9c1b2d4e 2026-11.jsonl";
    private static final String PHOTO = CloudAccount.MEDIA + "/Ana/a.jpg";

    @TempDir
    Path dir;
    private FakeDav account;
    private Map<String, String> portableWere, keysWere;
    private final List<CountDownLatch> held = new java.util.ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        account = new FakeDav("u", "p");
        portableWere = Settings.get().portable();
        keysWere = VaultComputer.kept();
    }

    @AfterEach
    void stop() {
        held.forEach(CountDownLatch::countDown); // nothing waits past the test
        account.close();
        Settings.get().replacePortable(portableWere, k -> false);
        keysWere.forEach(Settings.get()::set);
    }

    /** The one computer of these tests: its settings stay in for the whole test, so its rounds can run while the test goes on. */
    private VaultComputer desk() throws IOException {
        return new VaultComputer(account, dir, DESK).write("texts/here/" + TEXT, "{}\n");
    }

    /** The next PUT of {@code path} held: the returned latch lets it go; {@code there} goes once it waits. */
    private CountDownLatch holdPut(String path, CountDownLatch there) {
        CountDownLatch latch = new CountDownLatch(1);
        held.add(latch);
        account.arrived.put(path, there);
        account.holdOnce.put(path, latch);
        return latch;
    }

    /** A round held in its media: its photo's upload waits until the returned latch goes. */
    private CountDownLatch roundInMedia(VaultComputer desk, CompletableFuture<?>[] round) throws InterruptedException {
        CountDownLatch there = new CountDownLatch(1);
        CountDownLatch release = holdPut(PHOTO, there);
        round[0] = desk.cloud.syncNow();
        there.await();
        return release;
    }

    @Test
    void theVaultsPageReadsWhileMediaGoesUp() throws Exception {
        VaultComputer desk = desk();
        CompletableFuture<?>[] round = {null};
        CountDownLatch release = roundInMedia(desk, round);
        VaultView view = desk.cloud.vault().vaultView().get();
        assertEquals(Cloud.VaultState.NONE, view.state());
        assertFalse(round[0].isDone(), "the round still held in its media");
        release.countDown();
        assertNull(((Cloud.Status) round[0].get()).error());
    }

    @Test
    void aVaultIsMadeWhileMediaGoesUp() throws Exception {
        VaultComputer desk = desk();
        CompletableFuture<?>[] round = {null};
        CountDownLatch release = roundInMedia(desk, round);
        assertNotNull(desk.cloud.vault().createVault().get());
        assertFalse(round[0].isDone(), "the round still held in its media");
        assertTrue(account.files.containsKey(Vault.INFO), "the vault made");
        release.countDown();
        round[0].get();
    }

    @Test
    void aVaultMadeWhileMediaGoesUpIsNeverToldAsNoneWhenTheRoundEnds() throws Exception {
        VaultComputer desk = desk();
        CompletableFuture<?>[] round = {null};
        CountDownLatch release = roundInMedia(desk, round);
        desk.cloud.vault().createVault().get();
        release.countDown();
        Cloud.Status s = (Cloud.Status) round[0].get();
        assertEquals(Cloud.VaultState.OPEN, s.vault(), "the round read the vault before it was made; the state told is today's");
        assertEquals(Cloud.VaultState.OPEN, desk.cloud.vaultState());
    }

    @Test
    void aJobAskedDuringTheRoundsVaultPartWaitsForItAndNotForItsMedia() throws Exception {
        VaultComputer desk = desk();
        CountDownLatch inVaultPart = new CountDownLatch(1), inMedia = new CountDownLatch(1);
        CountDownLatch letSettingsGo = holdPut(CloudAccount.SETTINGS_FILE, inVaultPart);
        CountDownLatch letMediaGo = holdPut(PHOTO, inMedia);
        CompletableFuture<Cloud.Status> round = desk.cloud.syncNow();
        inVaultPart.await();
        int before = account.log.size();
        CompletableFuture<VaultView> view = desk.cloud.vault().vaultView();
        while (!desk.cloud.vaultJobWaiting() && !view.isDone()) Thread.onSpinWait(); // asked, and either held or (wrongly) run
        assertFalse(view.isDone(), "the view waited for the round's vault part");
        letSettingsGo.countDown();
        assertEquals(Cloud.VaultState.NONE, view.get().state());
        assertFalse(round.isDone(), "the view came before the media ended");
        List<String> log = List.copyOf(account.log);
        int viewRead = log.subList(before, log.size()).indexOf("GET " + Vault.INFO) + before;
        assertTrue(viewRead >= before, "the view read the vault: " + log);
        int lastVaultPart = -1;
        for (int i = 0; i < log.size(); i++) if (log.get(i).startsWith("PUT ") && !log.get(i).startsWith("PUT " + CloudAccount.MEDIA)) lastVaultPart = i;
        assertTrue(log.contains("PUT " + CloudAccount.SETTINGS_FILE), "the vault part wrote the settings: " + log);
        assertTrue(viewRead > lastVaultPart, "the view read the vault only once the round's vault part was over: " + log);
        letMediaGo.countDown();
        assertNull(round.get().error());
    }

    /** A round held on its first photo; once let go, it holds on the second, its progress told for it in between. */
    private CompletableFuture<Cloud.Status> roundHeldOnTwoPhotos(VaultComputer desk, CountDownLatch[] second) throws InterruptedException, IOException {
        desk.write("Ana/b.jpg", "otra foto");
        CountDownLatch atFirst = new CountDownLatch(1), atSecond = new CountDownLatch(1);
        CountDownLatch first = holdPut(PHOTO, atFirst);
        second[0] = holdPut(CloudAccount.MEDIA + "/Ana/b.jpg", atSecond);
        CompletableFuture<Cloud.Status> round = desk.cloud.syncNow();
        atFirst.await();
        second[1] = atSecond;
        second[2] = first;
        return round;
    }

    @Test
    void theStateAJobReadDuringMediaIsToldAtOnce() throws Exception {
        VaultComputer desk = desk();
        CompletableFuture<?>[] round = {null};
        CountDownLatch release = roundInMedia(desk, round);
        desk.cloud.vault().createVault().get();
        desk.cloud.vault().vaultView().get();
        assertEquals(Cloud.VaultState.OPEN, desk.cloud.status().vault(), "the page reads the status: never the state from before the job");
        release.countDown();
        round[0].get();
    }

    @Test
    void theRoundsProgressNeverTellsTheStateFromBeforeAJob() throws Exception {
        VaultComputer desk = desk();
        CountDownLatch[] holds = new CountDownLatch[3];
        CompletableFuture<Cloud.Status> round = roundHeldOnTwoPhotos(desk, holds);
        desk.cloud.vault().createVault().get();
        desk.cloud.textsNow().get(); // reads the vault's state (open now) and tells no status of its own
        holds[2].countDown();
        holds[1].await(); // the second photo's upload told before it went
        assertEquals(Cloud.Step.UPLOADING, desk.cloud.status().now().step(), "mid-round");
        assertEquals(Cloud.VaultState.OPEN, desk.cloud.status().vault(), "each progress tick says the state last read, never the round's first");
        holds[0].countDown();
        assertEquals(Cloud.VaultState.OPEN, round.get().vault());
    }

    @Test
    void aStateThatCantBeReadAgainAfterMediaIsTheLastOneRead() throws Exception {
        VaultComputer desk = desk();
        CompletableFuture<?>[] round = {null};
        CountDownLatch release = roundInMedia(desk, round);
        desk.cloud.vault().createVault().get();
        desk.cloud.vault().vaultView().get(); // as the page does after making it
        account.failReadOnce.add(Vault.INFO); // the round's look again after media fails
        release.countDown();
        assertEquals(Cloud.VaultState.OPEN, ((Cloud.Status) round[0].get()).vault(), "the state last read, not the one from before the vault");
        assertFalse(account.failReadOnce.contains(Vault.INFO), "the look again was asked, and failed");
    }

    @Test
    void aJobWaitingWhileTheAccountIsDisconnectedRunsAgainstNone() throws Exception {
        VaultComputer desk = desk();
        CountDownLatch inVaultPart = new CountDownLatch(1);
        CountDownLatch letGo = holdPut(CloudAccount.SETTINGS_FILE, inVaultPart);
        CompletableFuture<Cloud.Status> round = desk.cloud.syncNow();
        inVaultPart.await();
        CompletableFuture<String> made = desk.cloud.vault().createVault();
        while (!desk.cloud.vaultJobWaiting() && !made.isDone()) Thread.onSpinWait();
        desk.connected = false; // Ajustes → Nube → Desconectar meanwhile
        letGo.countDown();
        java.util.concurrent.ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class, made::get);
        assertEquals(app.unichat.support.I18n.t("cloud.error.vaultNotOpen"), e.getCause().getMessage());
        assertFalse(account.files.containsKey(Vault.INFO), "no vault made in an account no longer this computer's");
        round.get();
    }

    @Test
    void aStateToldAfterAJobNeverPutsBackTheRoundsProgressOverItsEnd() throws Exception {
        VaultComputer desk = desk();
        CompletableFuture<?>[] round = {null};
        CountDownLatch release = roundInMedia(desk, round);
        desk.cloud.vault().createVault().get();
        desk.cloud.beforeTold = () -> { // the view read the status mid-round; the round ends before it tells its state
            release.countDown();
            try {
                round[0].get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        desk.cloud.vault().vaultView().get();
        assertTrue(round[0].isDone());
        assertNull(desk.cloud.status().now(), "the round's end stays told: no \"uploading\" left from its middle");
        assertEquals(Cloud.VaultState.OPEN, desk.cloud.status().vault());
    }

    @Test
    void aStateChangedByAJobDropsTheNoticeOfTheOneBefore() throws Exception {
        VaultComputer desk = desk();
        desk.cloud.vault().createVault().get();
        account.put(Vault.ROOT + "/Settings/unichat-transcripts.json", "dañado".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Cloud.Status s = desk.cloud.syncNow().get();
        assertEquals(Cloud.VaultState.OPEN, s.vault());
        assertNotNull(s.vaultNotice(), "the damaged part said");
        desk.cloud.vault().forgetHere().get();
        desk.cloud.vault().vaultView().get(); // reads it locked here now
        assertEquals(Cloud.VaultState.LOCKED, desk.cloud.status().vault());
        assertNull(desk.cloud.status().vaultNotice(), "what the open vault left is no longer what the page shows");
    }
}

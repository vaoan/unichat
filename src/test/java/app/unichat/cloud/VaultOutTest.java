package app.unichat.cloud;

import app.unichat.settings.Settings;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning the vault off: everything with a plain place goes back there, read back, and only then the vault is deleted;
 * the other computers find it gone and go back to plain by themselves. Media is never touched.
 */
class VaultOutTest {
    private static final String DESK = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44";
    private static final String LAPTOP = "2c7b4a4f-1e2d-4d1f-8b62-7a3e8d2f3c55";
    private static final String OTHER = "3d8c5b5a-2f3e-4e2a-9c73-8b4f9e3a4d66";
    private static final String TEXT = "3f2a9c1b2d4e 2026-11.jsonl";
    private static final String TRANSCRIPT = "a".repeat(64) + ".txt";
    private static final String SETTINGS = CloudAccount.SETTINGS_FILE;
    private static final String AI = "Unichat/Settings/unichat-ai.json";
    private static final String PHOTO = CloudAccount.MEDIA + "/Ana/a.jpg";

    @TempDir
    Path dir;
    private FakeDav cloud;
    private Map<String, String> portableWere;
    private Map<String, String> keysWere;
    private Vault.Created made;

    @BeforeEach
    void start() throws Exception {
        cloud = new FakeDav("u", "p");
        portableWere = Settings.get().portable();
        keysWere = VaultComputer.kept();
        made = Vault.create(dav(), DESK, "Escritorio");
    }

    @AfterEach
    void stop() {
        cloud.close();
        Settings.get().replacePortable(portableWere, k -> false);
        keysWere.forEach(Settings.get()::set);
    }

    private Storage dav() {
        return new WebDav(cloud.url(), "u", "p");
    }

    private VaultComputer desk() throws IOException {
        return new VaultComputer(cloud, dir, DESK).opened(made.info(), made.computer()).set("ui.theme", "DARK")
            .write("writing-style.md", "escribo corto").write("texts/here/" + TEXT, "{\"d\":1}\n")
            .write("pinned-messages.json", "{\"!c\":[]}").write("transcripts/" + TRANSCRIPT, "hola")
            .set("server.telegram.apiId", "12345");
    }

    private String plain(String path) {
        byte[] b = cloud.files.get(path);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private boolean anyVault() {
        return cloud.files.keySet().stream().anyMatch(p -> p.startsWith(Vault.ROOT + "/")) || cloud.dirs.contains(Vault.ROOT);
    }

    /** A computer of the vault that's off now: what it sent while it ran, sealed. */
    private void offlineLaptopSent(String text) throws IOException {
        Vault.addComputer(dav(), made.vk(), LAPTOP, "Portátil");
        SealedStorage sealed = new SealedStorage(dav(), () -> made.vk().clone());
        String folder = TextSync.ROOT + "/" + LAPTOP;
        sealed.mkdirs(folder);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        sealed.put(folder + "/" + TEXT, data);
        sealed.put(folder + "/index.json", TextIndex.write(Map.of(TEXT, (long) data.length)));
    }

    @Test
    void turningOffPutsEverythingBackPlainBeforeDeletingTheVault() throws Exception {
        VaultComputer desk = desk();
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        offlineLaptopSent("{\"l\":1}\n");
        Map<String, byte[]> before = new HashMap<>(cloud.files);

        cloud.failOnce.add(TextSync.ROOT + "/" + LAPTOP + "/" + TEXT); // the network drops on the last plain copy
        ExecutionException failed = assertThrows(ExecutionException.class, () -> desk.as(desk.cloud.vault()::turnOffVault));
        assertNotNull(failed.getCause().getMessage(), "the failure is told");
        assertTrue(failed.getCause().getMessage().contains(app.unichat.support.I18n.t("cloud.error.vaultOffUndone", "")),
            "says the vault is as it was: " + failed.getCause().getMessage());
        before.forEach((p, b) -> assertArrayEquals(b, cloud.files.get(p), "as it was: " + p));
        assertEquals(before.keySet(), cloud.files.keySet(), "and what was copied plain before the failure taken back");
        assertEquals(made.info().id(), desk.get(VaultParts.ID), "still held here");
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault(), "and still open: the person can try again");

        desk.as(desk.cloud.vault()::turnOffVault); // again: this time it goes through
        assertFalse(anyVault(), "the vault deleted, whole");
        Bundle settings = Bundle.parse(cloud.files.get(SETTINGS));
        assertNotNull(settings, "the settings back plain");
        assertEquals("DARK", settings.settings().get("ui.theme"));
        assertTrue(plain(AI).contains("escribo corto"), "what the AI learned");
        assertEquals("{\"d\":1}\n", plain(TextSync.ROOT + "/" + DESK + "/" + TEXT), "this computer's texts");
        assertEquals("{\"l\":1}\n", plain(TextSync.ROOT + "/" + LAPTOP + "/" + TEXT), "an offline computer's texts survive");
        assertEquals(Map.of(TEXT, 8L), TextIndex.read(cloud.files.get(TextSync.ROOT + "/" + LAPTOP + "/index.json")), "with its index");
        assertArrayEquals(before.get(PHOTO), cloud.files.get(PHOTO), "media untouched");
        assertEquals(before.get(PHOTO).length, cloud.files.get(PHOTO).length);
        for (String p : cloud.files.keySet()) {
            assertFalse(p.contains("secrets") || p.contains("pinned") || p.contains("transcripts"), "the vault's own parts never go plain: " + p);
            assertFalse(new String(cloud.files.get(p), StandardCharsets.ISO_8859_1).contains("12345"), "no secret plain: " + p);
        }
        for (String k : new String[] {VaultParts.ID, VaultParts.KEY, VaultParts.PRIVATE, VaultParts.DECLINED, VaultPeers.KEY,
            VaultMove.MOVED_AT, VaultActions.UNCONFIRMED}) {
            assertNull(desk.get(k), "forgotten here: " + k);
        }

        String own = TextSync.ROOT + "/" + DESK + "/" + TEXT;
        int sent = cloud.versions.get(own);
        Cloud.Status s = desk.round();
        assertNull(s.error());
        assertEquals(Cloud.VaultState.NONE, s.vault(), "today's plain round");
        assertEquals(sent, cloud.versions.get(own), "what was just copied isn't sent again");
    }

    @Test
    void aPlainCopyLetGoMeanwhileIsPutBackOnceTheVaultIsGone() throws Exception {
        offlineLaptopSent("{\"l\":1}\n");
        String text = TextSync.ROOT + "/" + LAPTOP + "/" + TEXT;
        Storage plain = new Through(dav()) {
            @Override public void delete(String path) throws IOException {
                // Another computer's round, still open, lets the plain copy go: its sealed one is as long.
                if (path.equals(Vault.ROOT)) cloud.files.remove(text);
                super.delete(path);
            }
        };
        assertTrue(VaultOut.run(plain, new SealedStorage(dav(), () -> made.vk().clone()), made.info(), made.vk()));
        assertFalse(anyVault());
        assertEquals("{\"l\":1}\n", plain(text), "put back after the vault went");
    }

    @Test
    void aPartThatDoesntOpenIsLeft() throws Exception {
        cloud.dirs.add(Vault.ROOT + "/Settings");
        cloud.put(Vault.ROOT + "/Settings/unichat-ai.json", "dañado".getBytes(StandardCharsets.UTF_8));
        SealedStorage sealed = new SealedStorage(dav(), () -> made.vk().clone());
        sealed.put(SETTINGS, Bundle.of(DESK, java.time.Instant.now(), Map.of("ui.theme", "DARK"), null).toBytes());
        assertTrue(VaultOut.run(dav(), sealed, made.info(), made.vk()));
        assertNull(cloud.files.get(AI), "no one can read it: nothing plain");
        assertNotNull(cloud.files.get(SETTINGS), "the others still go");
        assertFalse(anyVault());
    }

    @Test
    void aVaultFolderThatWontGoStillEndsTheVaultEverywhere() throws Exception {
        VaultComputer desk = desk();
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        cloud.failDeleteOnce.add(Vault.ROOT);
        ExecutionException failed = assertThrows(ExecutionException.class, () -> desk.as(desk.cloud.vault()::turnOffVault));
        assertEquals(app.unichat.support.I18n.t("cloud.error.vaultLeftovers"), failed.getCause().getMessage(), "told apart");
        assertFalse(cloud.files.containsKey(Vault.INFO), "its file gone: no computer finds a vault");
        assertNotNull(cloud.files.get(SETTINGS), "everything plain");
        assertNull(desk.get(VaultParts.ID), "forgotten here all the same");
        assertEquals(Cloud.VaultState.NONE, desk.round().vault());
    }

    @Test
    void aVaultThatWontGoAtAllSaysItMayBePartlyGone() throws Exception {
        VaultComputer desk = desk();
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        cloud.failDeleteOnce.add(Vault.ROOT);
        cloud.failDeleteOnce.add(Vault.INFO);
        ExecutionException failed = assertThrows(ExecutionException.class, () -> desk.as(desk.cloud.vault()::turnOffVault));
        assertEquals(app.unichat.support.I18n.t("cloud.error.vaultPartlyGone"), failed.getCause().getMessage(), "never \"as it was\"");
        assertNotNull(cloud.files.get(SETTINGS), "everything plain");
        assertEquals("{\"d\":1}\n", plain(TextSync.ROOT + "/" + DESK + "/" + TEXT));
    }

    @Test
    void aSettingsPartRewrittenMeanwhileIsNotPutBack() throws Exception {
        SealedStorage sealed = new SealedStorage(dav(), () -> made.vk().clone());
        sealed.put(SETTINGS, Bundle.of(DESK, java.time.Instant.now(), Map.of("ui.theme", "DARK", "ui.x", "y".repeat(40)), null).toBytes());
        byte[] shorter = Bundle.of(OTHER, java.time.Instant.now(), Map.of("ui.theme", "LIGHT"), null).toBytes();
        Storage plain = new Through(dav()) {
            @Override public void delete(String path) throws IOException {
                if (path.equals(Vault.ROOT)) cloud.put(SETTINGS, shorter); // a computer, plain already, sent its own
                super.delete(path);
            }
        };
        assertTrue(VaultOut.run(plain, sealed, made.info(), made.vk()));
        assertArrayEquals(shorter, cloud.files.get(SETTINGS), "a bundle isn't a text: shorter is no sign of less");
    }

    @Test
    void aCopyPutBackThatDoesntReadBackIsTold() throws Exception {
        offlineLaptopSent("{\"l\":1}\n");
        String text = TextSync.ROOT + "/" + LAPTOP + "/" + TEXT;
        boolean[] gone = {false};
        Storage plain = new Through(dav()) {
            @Override public void delete(String path) throws IOException {
                if (path.equals(Vault.ROOT)) {
                    cloud.files.remove(text); // let go meanwhile
                    gone[0] = true;
                }
                super.delete(path);
            }

            @Override public void put(String path, byte[] data) throws IOException {
                if (gone[0] && path.equals(text)) cloud.put(text, new byte[] {'{'}); // landed cut short
                else super.put(path, data);
            }
        };
        IOException e = assertThrows(IOException.class, () -> VaultOut.run(plain, new SealedStorage(dav(), () -> made.vk().clone()), made.info(), made.vk()));
        assertEquals(app.unichat.support.I18n.t("cloud.error.vaultNotBack"), e.getMessage());
    }

    @Test
    void turningOffNeedsTheVaultOpenHere() throws Exception {
        VaultComputer other = new VaultComputer(cloud, dir, OTHER);
        Map<String, byte[]> before = new HashMap<>(cloud.files);
        assertThrows(ExecutionException.class, () -> other.as(other.cloud.vault()::turnOffVault));
        assertEquals(before.keySet(), cloud.files.keySet(), "nothing touched");
    }

    @Test
    void anotherComputerFindsItGoneAndGoesBackByItself() throws Exception {
        VaultComputer desk = desk();
        Vault.Computer laptopWay = Vault.addComputer(dav(), made.vk(), LAPTOP, "Portátil");
        VaultComputer laptop = new VaultComputer(cloud, dir, LAPTOP).opened(made.info(), laptopWay).write("texts/here/" + TEXT, "{\"l\":1}\n");
        VaultComputer declined = new VaultComputer(cloud, dir, OTHER).set(VaultParts.DECLINED, "true").set("ui.theme", "LIGHT");
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        assertEquals(Cloud.VaultState.DECLINED, declined.round().vault());

        desk.as(desk.cloud.vault()::turnOffVault);
        Cloud.Status s = laptop.round();
        assertNull(s.error());
        assertEquals(Cloud.VaultState.NONE, s.vault(), "it finds the vault gone");
        assertNull(laptop.get(VaultParts.ID));
        assertNull(laptop.get(VaultParts.KEY), "and forgets its key");
        assertNull(laptop.get(VaultParts.PRIVATE));
        assertEquals("{\"l\":1}\n", plain(TextSync.ROOT + "/" + LAPTOP + "/" + TEXT), "its texts plain");
        assertEquals("DARK", laptop.get("ui.theme"), "the settings, plain, as they were in the vault");

        assertEquals(Cloud.VaultState.MISSING, declined.round().vault(), "one that went on without it holds no key to believe the mark with");
        declined.as(declined.cloud.vault()::leaveMissingVault); // asked once
        assertEquals(Cloud.VaultState.NONE, declined.round().vault(), "then back to plain");
        assertNull(declined.get(VaultParts.DECLINED), "never declined a vault that's gone");
        Vault.Created later = Vault.create(dav(), DESK, "Escritorio");
        assertEquals(Cloud.VaultState.LOCKED, declined.round().vault(), "a vault made later is asked about afresh");
        Arrays.fill(later.vk(), (byte) 0);
    }

    private VaultComputer laptopOpen() throws IOException, InterruptedException, ExecutionException {
        Vault.Computer laptopWay = Vault.addComputer(dav(), made.vk(), LAPTOP, "Portátil");
        VaultComputer laptop = new VaultComputer(cloud, dir, LAPTOP).opened(made.info(), laptopWay).write("texts/here/" + TEXT, "{\"l\":1}\n")
            .set("ui.theme", "SEPIA");
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        return laptop;
    }

    @Test
    void theVaultsFileDeletedByHandSendsNothingPlain() throws Exception {
        VaultComputer laptop = laptopOpen();
        cloud.files.remove(Vault.INFO); // someone with only the account
        laptop.write("texts/here/" + TEXT, "{\"l\":1}\n{\"l\":2}\n");
        int puts = cloud.puts.get();
        Cloud.Status s = laptop.round();
        assertNotEquals(Cloud.VaultState.NONE, s.vault(), "never back to plain on that alone");
        assertEquals(puts, cloud.puts.get(), "nothing sent, plain or sealed");
        assertNull(plain(TextSync.ROOT + "/" + LAPTOP + "/" + TEXT));
        assertEquals(made.info().id(), laptop.get(VaultParts.ID), "its hold kept");
        assertNotNull(laptop.get(VaultParts.KEY));
    }

    @Test
    void anOffMarkerWrittenByTheAccountIsNotBelieved() throws Exception {
        VaultComputer laptop = laptopOpen();
        cloud.files.remove(Vault.INFO);
        cloud.put("Unichat/Settings/vault-off.json", ("{\"vault\":\"" + made.info().id() + "\",\"generation\":0,\"mac\":\"AAAA\"}")
            .getBytes(StandardCharsets.UTF_8));
        int puts = cloud.puts.get();
        assertNotEquals(Cloud.VaultState.NONE, laptop.round().vault());
        assertEquals(puts, cloud.puts.get());
    }

    @Test
    void aVaultTurnedOffLeavesAMarkThatTheNextVaultClears() throws Exception {
        VaultComputer desk = desk();
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        desk.as(desk.cloud.vault()::turnOffVault);
        assertNotNull(cloud.files.get("Unichat/Settings/vault-off.json"), "turned off, and said so outside the vault");
        Vault.Created later = Vault.create(dav(), DESK, "Escritorio");
        assertNull(cloud.files.get("Unichat/Settings/vault-off.json"), "a new vault clears it");
        Arrays.fill(later.vk(), (byte) 0);
    }

    @Test
    void aVaultNoComputerHereCanOpenIsDeletedAndTheOthersAreAskedNotSentPlain() throws Exception {
        VaultComputer desk = desk();
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        assertThrows(ExecutionException.class, () -> desk.as(desk.cloud.vault()::deleteLockedVault), "never one open here");
        assertTrue(anyVault());

        VaultComputer other = new VaultComputer(cloud, dir, OTHER);
        assertEquals(Cloud.VaultState.LOCKED, other.round().vault());
        other.as(other.cloud.vault()::deleteLockedVault);
        assertFalse(anyVault(), "deleted, whole");
        assertEquals(Cloud.VaultState.NONE, other.round().vault(), "this one starts again without it");
        int puts = cloud.puts.get();
        assertEquals(Cloud.VaultState.MISSING, desk.round().vault(), "one that held it is asked, nothing said it was turned off");
        assertEquals(puts, cloud.puts.get(), "and sends nothing meanwhile");
    }

    /** A computer that saw the vault and never opened it: the file deleted by hand is no sign it was turned off. */
    private void neverOpenedFindsTheFileGone(VaultComputer other, Cloud.VaultState was) throws IOException, InterruptedException, ExecutionException {
        other.write("texts/here/" + TEXT, "{\"o\":1}\n").set("ui.theme", "LIGHT");
        assertEquals(was, other.round().vault());
        cloud.files.remove(Vault.INFO); // someone with only the account
        int puts = cloud.puts.get();
        assertEquals(Cloud.VaultState.MISSING, other.round().vault(), "never back to plain on that alone");
        assertEquals(Cloud.VaultState.MISSING, other.round().vault(), "nor the round after");
        assertEquals(puts, cloud.puts.get(), "nothing sent, plain or sealed");
        assertNull(plain(TextSync.ROOT + "/" + OTHER + "/" + TEXT));
        assertNull(cloud.files.get(SETTINGS));

        other.as(other.cloud.vault()::leaveMissingVault); // the person says it's gone, asked twice on the page
        assertEquals(Cloud.VaultState.NONE, other.round().vault(), "then today's plain round");
        assertEquals("{\"o\":1}\n", plain(TextSync.ROOT + "/" + OTHER + "/" + TEXT), "its texts plain");
        assertNull(other.get(VaultParts.DECLINED));
        assertNull(other.get(VaultParts.SEEN), "the vault it saw forgotten");
    }

    @Test
    void aComputerThatDeclinedSendsNothingPlainWhenTheVaultsFileIsDeletedByHand() throws Exception {
        neverOpenedFindsTheFileGone(new VaultComputer(cloud, dir, OTHER).set(VaultParts.DECLINED, "true"), Cloud.VaultState.DECLINED);
    }

    @Test
    void aComputerThatNeverOpenedTheVaultSendsNothingPlainWhenItsFileIsDeletedByHand() throws Exception {
        neverOpenedFindsTheFileGone(new VaultComputer(cloud, dir, OTHER), Cloud.VaultState.LOCKED);
    }

    @Test
    void aComputerThatForgotTheVaultHereStillRemembersItSawOne() throws Exception {
        VaultComputer laptop = laptopOpen();
        laptop.as(laptop.cloud.vault()::forgetHere);
        assertEquals(Cloud.VaultState.LOCKED, laptop.round().vault());
        cloud.files.remove(Vault.INFO);
        assertEquals(Cloud.VaultState.MISSING, laptop.round().vault(), "forgotten here is not gone from the account");
    }

    @Test
    void aComputerThatNeverSawAVaultIsTodaysRound() throws Exception {
        cloud.files.remove(Vault.INFO);
        assertEquals(Cloud.VaultState.NONE, new VaultComputer(cloud, dir, OTHER).round().vault());
    }

    @Test
    void anotherVaultSeenReplacesTheOneSeenBefore() throws Exception {
        VaultComputer other = new VaultComputer(cloud, dir, OTHER);
        assertEquals(Cloud.VaultState.LOCKED, other.round().vault());
        assertEquals(made.info().id(), other.get(VaultParts.SEEN));
        cloud.files.remove(Vault.INFO);
        Vault.Created later = Vault.create(dav(), DESK, "Escritorio");
        assertEquals(Cloud.VaultState.LOCKED, other.round().vault());
        assertEquals(later.info().id(), other.get(VaultParts.SEEN));
        Arrays.fill(later.vk(), (byte) 0);
    }
}

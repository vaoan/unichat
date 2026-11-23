package app.unichat.cloud;

import app.unichat.settings.Settings;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vault moving from its old names ("Bóveda", "llaves", "boveda.json", sealed under "Ajustes", "Textos", "Registro") to
 * the English ones, every file sealed again under its new path: it opens before, halfway and after, and nothing is lost.
 */
class VaultNamesTest {
    private static final String DESK = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44", LAPTOP = "2c7b4a4f-1e2d-4d1f-8b62-7a3e8d2f3c55",
        THIRD = "3d8c5b5a-2f3e-4e2a-9c73-8b4f9e3a4d66", PLAIN = "4e9d6c6b-3a4f-4f3b-8d84-9c5a0f4b5e77";
    private static final String TEXT = "3f2a9c1b2d4e 2026-11.jsonl", TRANSCRIPT = "a".repeat(64) + ".txt";

    @TempDir
    Path dir;
    private FakeDav cloud;
    private Map<String, String> portableWere, keysWere;
    private Vault.Created made;
    private VaultComputer desk, laptop;

    @BeforeEach
    void start() throws Exception {
        cloud = new FakeDav("u", "p");
        portableWere = Settings.get().portable();
        keysWere = VaultComputer.kept();
        for (String k : VaultComputer.KEYS) Settings.get().set(k, null);
    }

    @AfterEach
    void stop() {
        VaultNames.beforeSwitch = null;
        VaultNames.afterSwitch = null;
        cloud.close();
        Settings.get().replacePortable(portableWere, k -> false);
        keysWere.forEach(Settings.get()::set);
        if (made != null) java.util.Arrays.fill(made.vk(), (byte) 0);
    }

    private Storage dav() {
        return new WebDav(cloud.url(), "u", "p");
    }

    /**
     * Two computers of a vault with everything in it (settings, the AI, secrets, pins, transcripts, both computers' texts,
     * a computer's plain texts moved in and so the list of who sent plain), all of it then put under the old names, as
     * the person's account is today; what each file of the vault holds, by its new logical path.
     */
    private Map<String, byte[]> anOldVault() throws VaultCrypto.Broken, IOException, Vault.Exists, InterruptedException, java.util.concurrent.ExecutionException {
        VaultComputer plain = new VaultComputer(cloud, dir, PLAIN).write("texts/here/" + TEXT, "{\"de\":\"antes\"}\n");
        plain.round(); // a computer sending plain before the vault
        made = Vault.create(dav(), DESK, "Escritorio");
        desk = new VaultComputer(cloud, dir, DESK).madeHere(made).set("ui.theme", "DARK").set("server.telegram.apiId", "12345")
            .write("writing-style.md", "escribo corto").write("texts/here/" + TEXT, "{\"de\":\"mesa\"}\n")
            .write("pinned-messages.json", "{\"!c\":[]}").write("transcripts/" + TRANSCRIPT, "hola");
        laptop = new VaultComputer(cloud, dir, LAPTOP).opened(made.info(), Vault.addComputer(dav(), made.vk(), LAPTOP, "Portátil"))
            .write("texts/here/" + TEXT, "{\"de\":\"portátil\"}\n");
        assertNull(desk.round().error());
        assertNull(laptop.round().error());
        assertNull(desk.round().error());
        Map<String, byte[]> held = opened(Vault.ROOT, made.vk(), false);
        assertTrue(held.containsKey(VaultMove.SENDERS) && held.containsKey("Unichat/Texts/" + PLAIN + "/" + TEXT), held.keySet().toString());
        toOldNames(made.vk());
        return held;
    }

    /** Every sealed file under {@code root}, opened (under its old path when {@code old}), by its new logical path. */
    private Map<String, byte[]> opened(String root, byte[] vk, boolean old) throws VaultCrypto.Broken {
        Map<String, byte[]> out = new TreeMap<>();
        for (Map.Entry<String, byte[]> f : new HashMap<>(cloud.files).entrySet()) {
            String p = f.getKey();
            if (!p.startsWith(root + "/")) continue;
            String stored = old ? CloudNames.newStored(p) : p;
            if (stored.startsWith(Vault.SLOTS + "/") || stored.equals(Vault.INFO)) continue;
            String logical = CloudAccount.ROOT + stored.substring(Vault.ROOT.length());
            out.put(logical, VaultCrypto.open(vk, old ? CloudNames.old(logical) : logical, f.getValue()));
        }
        return out;
    }

    /** The vault put back under its old names, each file sealed under its old logical path, its file without {@code names}. */
    private void toOldNames(byte[] vk) throws VaultCrypto.Broken {
        for (Map.Entry<String, byte[]> f : new HashMap<>(cloud.files).entrySet()) {
            String p = f.getKey();
            if (!p.startsWith(Vault.ROOT + "/")) continue;
            byte[] bytes = f.getValue();
            if (p.equals(Vault.INFO)) {
                Vault.Info info = Vault.parse(bytes);
                bytes = ("{\"id\":\"" + info.id() + "\",\"created\":\"" + info.created() + "\",\"generation\":" + info.generation() + "}")
                    .getBytes(StandardCharsets.UTF_8);
            } else if (!p.startsWith(Vault.SLOTS + "/")) {
                String logical = CloudAccount.ROOT + p.substring(Vault.ROOT.length());
                bytes = VaultCrypto.seal(vk, CloudNames.old(logical), VaultCrypto.open(vk, logical, bytes));
            }
            cloud.files.remove(p);
            cloud.put(CloudNames.oldStored(p), bytes);
        }
        cloud.dirs.removeIf(d -> d.equals(Vault.ROOT) || d.startsWith(Vault.ROOT + "/"));
    }

    private boolean anyUnder(String root) {
        return cloud.files.keySet().stream().anyMatch(p -> p.startsWith(root + "/"));
    }

    @Test
    void theVaultIsMovedAndResealedAndStillOpens() throws Exception {
        Map<String, byte[]> held = anOldVault();
        assertFalse(anyUnder(Vault.ROOT), "under the old names only, as the person's account is today");
        assertInstanceOf(OldVault.class, VaultNames.at(dav()));
        VaultComputer third = new VaultComputer(cloud, dir, THIRD);
        assertEquals(Cloud.VaultState.LOCKED, third.round().vault(), "a computer without its key finds the vault under the old names");
        assertFalse(anyUnder(Vault.ROOT), "and moves nothing: it can't seal anything again");

        Map<String, byte[]> before = new HashMap<>(cloud.files);
        Cloud.Status s = laptop.round();
        assertNull(s.error());
        assertEquals(Cloud.VaultState.OPEN, s.vault());
        assertFalse(anyUnder(CloudNames.OLD_VAULT), "nothing left under the old names: " + cloud.files.keySet());
        assertFalse(cloud.dirs.contains(CloudNames.OLD_VAULT));
        Map<String, byte[]> now = opened(Vault.ROOT, made.vk(), false);
        assertEquals(held.keySet(), now.keySet(), "every file, each opening under its new path");
        held.forEach((p, b) -> assertArrayEquals(b, now.get(p), p));
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(cloud.files.get(Vault.INFO)).has("names"),
            "the vault's file being there is the mark: nothing in it says the names");
        assertEquals(made.info().id(), Vault.info(dav()).id());
        assertEquals(java.util.Set.of(DESK, LAPTOP), Vault.computers(dav()).keySet(), "every way in moved");
        assertEquals(2, Vault.slots(dav()).size() - 1, "and the recovery code's");
        for (Map.Entry<String, byte[]> e : before.entrySet()) {
            String p = e.getKey();
            if (!p.startsWith(CloudNames.OLD_VAULT)) assertArrayEquals(e.getValue(), cloud.files.get(p), "outside the vault, nothing changed: " + p);
        }

        Map<String, byte[]> moved = new HashMap<>(cloud.files);
        s = desk.round();
        assertNull(s.error());
        assertEquals(Cloud.VaultState.OPEN, s.vault(), "the other computer opens it under the new names");
        assertEquals("DARK", desk.get("ui.theme"));
        assertEquals("{\"de\":\"portátil\"}\n", desk.read("texts/" + LAPTOP + "/" + TEXT));
        List<String> wrote = cloud.files.keySet().stream().filter(p -> !java.util.Arrays.equals(cloud.files.get(p), moved.get(p))).toList();
        assertEquals(List.of(), wrote, "and nothing goes up again: what it sees is what it sent");
        assertEquals(Cloud.VaultState.LOCKED, third.round().vault(), "the computer without the key finds it under the new names");
    }

    @Test
    void aVaultMigrationInterruptedHalfwayStillOpensAndFinishes() throws Exception {
        Map<String, byte[]> held = anOldVault();
        String settings = "Unichat/Vault/Settings/unichat-settings.json", drops = "Unichat/Vault/Texts/" + DESK + "/index.json";
        cloud.failOnce.add(drops); // the network drops during the copies
        laptop.set("ui.theme", "LIGHT");

        Cloud.Status s = laptop.round();
        assertNotNull(s.error(), "told");
        assertEquals(Cloud.VaultState.OPEN, s.vault(), "and still open, under the old names");
        assertTrue(cloud.files.containsKey(CloudNames.OLD_INFO), "the old names are still the vault's");
        assertFalse(cloud.files.containsKey(Vault.INFO));
        assertTrue(cloud.files.containsKey(settings), "halfway: some files copied already");
        cloud.failOnce.add(drops); // and again during the other computer's
        s = desk.round();
        assertEquals(Cloud.VaultState.OPEN, s.vault(), "another computer opens it halfway too");
        assertEquals("LIGHT", desk.get("ui.theme"), "and what changed halfway comes through");
        assertFalse(cloud.files.containsKey(Vault.INFO));

        cloud.failDeleteOnce.add("Unichat/Bóveda/Ajustes/unichat-ai.json"); // cut short again, after the switch
        s = laptop.round();
        assertNotNull(s.error());
        assertEquals(Cloud.VaultState.OPEN, s.vault());
        assertTrue(cloud.files.containsKey(Vault.INFO), "switched");
        assertFalse(cloud.files.containsKey(CloudNames.OLD_INFO), "the old names never open again");
        assertTrue(cloud.files.containsKey("Unichat/Bóveda/Ajustes/unichat-ai.json"), "one old file left behind");
        desk.set("ui.theme", "SYSTEM");

        s = desk.round();
        assertNull(s.error());
        assertEquals(Cloud.VaultState.OPEN, s.vault(), "it opens with leftovers under the old names");
        assertFalse(anyUnder(CloudNames.OLD_VAULT), "and finishes them: " + cloud.files.keySet());
        assertEquals(held.keySet(), opened(Vault.ROOT, made.vk(), false).keySet(), "nothing lost");
        assertNull(laptop.round().error());
        assertEquals("SYSTEM", laptop.get("ui.theme"), "the vault goes on under its new names");
        assertEquals("escribo corto", laptop.read("writing-style.md"));
    }

    @Test
    void aFileThatDoesntOpenIsLeftUnderItsOldName() throws Exception {
        anOldVault();
        cloud.put("Unichat/Bóveda/Ajustes/unichat-pinned.json", "dañado".getBytes(StandardCharsets.UTF_8));
        Cloud.Status s = laptop.round();
        assertEquals(Cloud.VaultState.OPEN, s.vault());
        assertTrue(cloud.files.containsKey(Vault.INFO), "the rest moved");
        assertTrue(cloud.files.containsKey("Unichat/Bóveda/Ajustes/unichat-pinned.json"), "never deleted: it may be the only copy");
        assertFalse(cloud.files.containsKey(CloudNames.OLD_INFO));
    }

    @Test
    void aRoundCutShortAfterTheSwitchGoesOnUnderTheNewNames() throws Exception {
        anOldVault();
        String drops = "Unichat/Vault/Texts/" + DESK + "/index.json";
        desk.set("ui.theme", "SYSTEM");
        cloud.failOnce.add(drops); // its own move cut short: the change goes up under the old names
        desk.round();
        assertFalse(cloud.files.containsKey(Vault.INFO));
        cloud.failDeleteOnce.add("Unichat/Bóveda/Textos/" + DESK + "/index.json"); // after the old settings went
        Cloud.Status s = laptop.round();
        assertNotNull(s.error());
        assertTrue(cloud.files.containsKey(Vault.INFO), "switched");
        assertEquals("SYSTEM", laptop.get("ui.theme"), "it took the other computer's change, never sending its own older one");
        assertNull(desk.round().error());
        assertNull(laptop.round().error());
        assertEquals("SYSTEM", desk.get("ui.theme"));
        assertEquals("SYSTEM", laptop.get("ui.theme"));
        assertFalse(anyUnder(CloudNames.OLD_VAULT));
    }

    @Test
    void aKeyChangeAcrossTheSwitchKeepsTheRemovedComputerOut() throws Exception {
        anOldVault();
        CountDownLatch read = new CountDownLatch(1), go = new CountDownLatch(1);
        cloud.arrived.put(CloudNames.OLD_INFO, read);
        cloud.holdReadOnce.put(CloudNames.OLD_INFO, go); // the job's first look at the vault, under the old names
        CompletableFuture<Cloud.KeyChanged> job = CompletableFuture.supplyAsync(() -> {
            try {
                return desk.as(() -> desk.cloud.vault().removeAndChangeKey(LAPTOP, java.util.Set.of(DESK)));
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        read.await();
        VaultNames.migrate(dav(), new OldVault(dav()), made.info(), made.vk()); // another computer moves the names meanwhile
        go.countDown();
        job.get();
        assertEquals(made.info().generation() + 1, Vault.info(dav()).generation(), "the key changed under the new names");
        assertFalse(Vault.computers(dav()).containsKey(LAPTOP), "and the computer removed there");
        assertFalse(anyUnder(CloudNames.OLD_VAULT), "nothing stranded under the old names: " + cloud.files.keySet());
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        assertEquals(Cloud.VaultState.REMOVED, laptop.round().vault());
    }

    @Test
    void aLateCopyBetweenTheSwitchAndTheDeletesNeverLosesTheOldOne() throws Exception {
        Map<String, byte[]> held = anOldVault();
        String text = "Unichat/Texts/" + DESK + "/" + TEXT, stored = Vault.ROOT + "/Texts/" + DESK + "/" + TEXT;
        AtomicReference<Vault.Info> oldNames = new AtomicReference<>();
        VaultNames.afterSwitch = () -> {
            try {
                oldNames.set(Vault.info(new OldVault(dav()))); // a computer still reading the old names
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            cloud.put(stored, VaultCrypto.seal(made.vk(), text, "{".getBytes(StandardCharsets.UTF_8))); // a late, shorter copy
        };
        assertNull(laptop.round().error());
        assertNull(oldNames.get(), "between the switch and the deletes, the old names open nothing");
        assertArrayEquals(held.get(text), VaultCrypto.open(made.vk(), text, cloud.files.get(stored)), "the longer old copy kept");
        assertFalse(anyUnder(CloudNames.OLD_VAULT));
    }

    @Test
    void textsAskedAloneDuringTheSwitchGoUnderTheNewNames() throws Exception {
        anOldVault();
        String later = "3f2a9c1b2d4e 2026-12.jsonl";
        laptop.write("texts/here/" + later, "{\"x\":1}\n");
        AtomicReference<CompletableFuture<Void>> alone = new AtomicReference<>();
        int[] switched = {-1};
        VaultNames.beforeSwitch = () -> {
            alone.set(laptop.cloud.textsNow());
            while (!laptop.cloud.keyWaiting()) Thread.onSpinWait(); // asked, and held behind the move
        };
        VaultNames.afterSwitch = () -> switched[0] = cloud.log.size();
        laptop.as(() -> {
            Cloud.Status s = laptop.cloud.syncNow().get();
            alone.get().get(); // the texts alone, done with this computer's settings still in
            return CompletableFuture.completedFuture(s);
        });
        assertTrue(switched[0] >= 0);
        assertEquals(List.of(), cloud.log.subList(switched[0], cloud.log.size()).stream().filter(("GET " + CloudNames.OLD_INFO)::equals).toList(),
            "nothing read the old names after the switch: the texts alone looked at the vault where it is now");
        assertTrue(cloud.files.containsKey(Vault.ROOT + "/Texts/" + LAPTOP + "/" + later));
        assertFalse(anyUnder(CloudNames.OLD_VAULT));
    }

    @Test
    void aSlotWrittenAfterTheSlotsWereCopiedReachesTheNewNames() throws Exception {
        anOldVault();
        VaultNames.beforeSwitch = () -> {
            try {
                Vault.addComputer(new OldVault(dav()), made.vk(), THIRD, "Tercero"); // another computer joins under the old names
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        cloud.failDeleteOnce.add("Unichat/Bóveda/Ajustes/unichat-ai.json"); // and the deletes are cut short
        laptop.round();
        assertTrue(Vault.computers(dav()).containsKey(THIRD), "its way in is under the new names");
    }

    @Test
    void aJobThatCantMoveTheVaultFirstIsRefusedAndChangesNothing() throws Exception {
        anOldVault();
        cloud.failOnce.add("Unichat/Vault/Texts/" + DESK + "/index.json");
        java.util.concurrent.ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
            () -> desk.as(() -> desk.cloud.vault().rotateKey()));
        assertTrue(e.getCause().getMessage().contains("próxima ronda"), e.getCause().getMessage());
        assertEquals(made.info().generation(), Vault.info(new OldVault(dav())).generation(), "the key unchanged");
        assertFalse(cloud.files.containsKey(Vault.INFO), "still under the old names, as it opens");
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault(), "the next round moves it");
        assertTrue(cloud.files.containsKey(Vault.INFO));
    }

    @Test
    void aComputerOpeningTheVaultUnderItsOldNamesMovesItAfter() throws Exception {
        anOldVault();
        VaultComputer third = new VaultComputer(cloud, dir, THIRD);
        assertEquals(Cloud.VaultState.LOCKED, third.round().vault());
        third.as(() -> third.cloud.vault().openVault(VaultView.Way.RECOVERY, made.code().shown().toCharArray()));
        assertTrue(cloud.files.containsKey(Vault.INFO), "opened, then moved in the same job");
        assertFalse(anyUnder(CloudNames.OLD_VAULT), cloud.files.keySet().toString());
        assertTrue(Vault.computers(dav()).containsKey(THIRD), "its way in under the new names");
        assertEquals(Cloud.VaultState.OPEN, third.round().vault());
        assertEquals("DARK", third.get("ui.theme"));
    }

    @Test
    void openingTheVaultsPageNeverRunsTheMove() throws Exception {
        anOldVault();
        cloud.failOnce.add("Unichat/Vault/Texts/" + DESK + "/index.json"); // a move would fail now
        VaultView view = desk.as(() -> desk.cloud.vault().vaultView());
        assertEquals(Cloud.VaultState.OPEN, view.state(), "it reads the vault where it is");
        assertFalse(cloud.files.containsKey(Vault.INFO), "and moves nothing");
        assertFalse(anyUnder(Vault.ROOT), cloud.files.keySet().toString());
        desk.as(() -> desk.cloud.vault().codeKept()); // nor does a job that changes no key or file refuse
    }

    /** The vault moved by the laptop, its finish cut short right after the switch: every old slot still there. */
    private void switchedWithTheOldSlotsLeft() throws VaultCrypto.Broken, InterruptedException, IOException, java.util.concurrent.ExecutionException, Vault.Exists {
        anOldVault();
        cloud.failDeleteOnce.add(CloudNames.OLD_SLOTS + "/" + DESK + ".json");
        assertNotNull(laptop.round().error());
        assertTrue(cloud.files.containsKey(Vault.INFO), "switched");
        assertTrue(cloud.files.containsKey(CloudNames.OLD_SLOTS + "/" + DESK + ".json"), "its old slots left");
    }

    @Test
    void aKeyChangedAfterAnInterruptedFinishKeepsThisComputerIn() throws Exception {
        switchedWithTheOldSlotsLeft();
        desk.as(() -> desk.cloud.vault().rotateKey());
        desk.round(); // finishes what the move left
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault(), "its old slot never went back over the new one");
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        assertEquals(made.info().generation() + 1, Vault.info(dav()).generation());
        assertFalse(anyUnder(CloudNames.OLD_SLOTS));
    }

    @Test
    void aWayInTakenAwayAfterAnInterruptedFinishStaysGone() throws Exception {
        switchedWithTheOldSlotsLeft();
        desk.as(() -> desk.cloud.vault().newRecoveryCode());
        desk.as(() -> desk.cloud.vault().removeComputer(LAPTOP));
        desk.round();
        assertEquals(1, Vault.slots(dav()).stream().filter(slot -> slot.method() == VaultSlot.Method.RECOVERY).count(),
            "the old recovery code never comes back");
        assertFalse(Vault.computers(dav()).containsKey(LAPTOP), "nor the computer removed");
        assertEquals(Cloud.VaultState.REMOVED, laptop.round().vault());
    }

    @Test
    void aComputerForgottenAfterAnInterruptedFinishStaysGone() throws Exception {
        switchedWithTheOldSlotsLeft();
        desk.as(() -> desk.cloud.vault().forgetHere());
        laptop.round(); // the other computer finishes the move
        assertFalse(Vault.computers(dav()).containsKey(DESK), "its way in never comes back");
        assertFalse(anyUnder(CloudNames.OLD_SLOTS));
    }

    @Test
    void anOldFileThatNeverReadsDoesntStopAJobOnceTheNamesAreSwitched() throws Exception {
        switchedWithTheOldSlotsLeft();
        cloud.failReads.add("Unichat/Bóveda/Textos/" + LAPTOP + "/index.json"); // left for the rounds to finish, or keep
        desk.as(() -> desk.cloud.vault().removeComputer(LAPTOP));
        assertFalse(Vault.computers(dav()).containsKey(LAPTOP), "removed: only the old slots needed finishing first");
        assertFalse(anyUnder(CloudNames.OLD_SLOTS), "and they were");
    }

    @Test
    void aFileThatNeverOpensIsLookedAtOnce() throws Exception {
        anOldVault();
        cloud.put("Unichat/Bóveda/Ajustes/unichat-pinned.json", "dañado".getBytes(StandardCharsets.UTF_8));
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        assertTrue(cloud.files.containsKey("Unichat/Bóveda/Ajustes/unichat-pinned.json"), "left under its old name");
        int asked = cloud.asked.size();
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        assertEquals(List.of(), cloud.asked.subList(asked, cloud.asked.size()).stream()
            .filter(a -> a.contains(CloudNames.OLD_VAULT + "/") && !a.equals("PROPFIND Unichat/Bóveda/Ajustes/unichat-pinned.json")).toList(),
            "the next round doesn't walk what's left again: only that file's stamp is asked");
    }
}

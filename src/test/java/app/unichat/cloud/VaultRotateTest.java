package app.unichat.cloud;

import app.unichat.settings.Settings;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Changing the vault's key: every computer still listed goes on by itself, a removed one gets nothing new, every file
 * opens with the new key and no longer with the old; removing a computer; forgetting the vault on this one.
 */
class VaultRotateTest {
    private static final String DESK = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44", LAPTOP = "2c7b4a4f-1e2d-4d1f-8b62-7a3e8d2f3c55",
        OTHER = "3d8c5b5a-2f3e-4e2a-9c73-8b4f9e3a4d66";
    private static final String TEXT = "3f2a9c1b2d4e 2026-11.jsonl";

    @TempDir
    Path dir;
    private FakeDav cloud;
    private Map<String, String> portableWere, keysWere;
    private Vault.Created made;
    private VaultComputer desk, laptop;
    private Vault.Computer laptopWay;

    @BeforeEach
    void start() throws Exception {
        cloud = new FakeDav("u", "p");
        portableWere = Settings.get().portable();
        keysWere = VaultComputer.kept();
        made = Vault.create(dav(), DESK, "Escritorio");
        laptopWay = Vault.addComputer(dav(), made.vk(), LAPTOP, "Portátil");
        desk = new VaultComputer(cloud, dir, DESK).opened(made.info(), made.computer()).set("ui.theme", "DARK")
            .write("texts/here/" + TEXT, "{}\n");
        laptop = new VaultComputer(cloud, dir, LAPTOP).opened(made.info(), laptopWay);
        assertNull(desk.round().error());
        assertNull(laptop.round().error());
        assertEquals("DARK", laptop.get("ui.theme"));
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

    private Cloud.KeyChanged rotate(VaultComputer c) throws Exception {
        return c.as(c.cloud.vault()::rotateKey);
    }

    private VaultSlot slot(String id) throws Exception {
        return Vault.slot(dav(), Vault.info(dav()), id);
    }

    /** Every file the vault keeps as a file of the account's (not its slots nor its own file), by its logical path. */
    private Map<String, byte[]> sealedFiles() {
        Map<String, byte[]> out = new HashMap<>();
        cloud.files.forEach((p, d) -> {
            if (p.startsWith(Vault.ROOT + "/") && !p.startsWith(Vault.SLOTS + "/") && !p.equals(Vault.INFO)) {
                out.put(CloudAccount.ROOT + p.substring(Vault.ROOT.length()), d);
            }
        });
        return out;
    }

    private static boolean opens(byte[] key, String path, byte[] sealed) {
        try {
            VaultCrypto.open(key, path, sealed);
            return true;
        } catch (VaultCrypto.Broken e) {
            return false;
        }
    }

    /** The vault's key as {@code c} holds it now, from its own slot and its key. */
    private byte[] keyOf(VaultComputer c) throws Exception {
        return slot(c.get("cloud.machine")).unwrap(Base64.getDecoder().decode(c.get(VaultParts.KEY)));
    }

    @Test
    void anotherComputerGoesOnAfterTheKeyChanges() throws Exception {
        Cloud.KeyChanged changed = rotate(desk);
        assertNotNull(changed.code());
        assertEquals(1, Vault.info(dav()).generation(), "the generation rises");
        assertEquals(1, slot(DESK).generation(), "the one that changed it took it at once");
        assertFalse(slot(DESK).params().has("next"));
        assertTrue(slot(LAPTOP).params().has("next"), "the other is offered the new key");
        assertEquals(0, slot(LAPTOP).generation());

        String laptopKey = laptop.get(VaultParts.KEY);
        Cloud.Status s = laptop.round();
        assertNull(s.error());
        assertEquals(Cloud.VaultState.OPEN, s.vault(), "it goes on, never opened again by hand");
        assertEquals(1, slot(LAPTOP).generation(), "its own slot taken to the new generation");
        assertFalse(slot(LAPTOP).params().has("next"), "and the offer gone");
        assertEquals(laptopKey, laptop.get(VaultParts.KEY), "its own key unchanged");
        assertArrayEquals(keyOf(desk), keyOf(laptop), "both on the same new key");
        assertFalse(java.util.Arrays.equals(made.vk(), keyOf(laptop)), "a new one");

        desk.set("ui.theme", "LIGHT");
        assertNull(desk.round().error());
        assertNull(laptop.round().error());
        assertEquals("LIGHT", laptop.get("ui.theme"), "and they go on agreeing");
    }

    @Test
    void aRemovedComputerReadsNothingNew() throws Exception {
        desk.as(() -> desk.cloud.vault().removeComputer(LAPTOP));
        assertNull(slot(LAPTOP), "its slot gone");
        rotate(desk);
        byte[] oldVk = made.vk();
        for (VaultSlot s : Vault.slots(dav())) {
            assertThrows(VaultCrypto.Broken.class, () -> s.unwrap(laptopWay.key()), "its key opens no slot");
            assertThrows(VaultCrypto.Broken.class, () -> s.unwrap(oldVk));
            if (s.params().has("next")) {
                byte[] sealed = Base64.getDecoder().decode(s.params().get("next").get("sealed").asText());
                assertFalse(opens(oldVk, "next:" + made.info().id() + ":" + s.id() + ":1", sealed), "the old key opens no offer");
            }
        }
        sealedFiles().forEach((p, d) -> assertFalse(opens(oldVk, p, d), "the old key opens no file: " + p));

        Map<String, byte[]> before = new HashMap<>(cloud.files);
        int puts = cloud.puts.get();
        Cloud.Status s = laptop.write("texts/here/" + TEXT, "{}\n{\"más\":1}\n").set("ui.theme", "SEPIA").round();
        assertEquals(Cloud.VaultState.REMOVED, s.vault());
        assertEquals(puts, cloud.puts.get(), "it sends nothing");
        assertEquals(before.keySet(), cloud.files.keySet(), "and deletes nothing");
    }

    @Test
    void oldFilesStillOpenAfterRotationWithTheNewKey() throws Exception {
        Map<String, byte[]> before = sealedFiles();
        Map<String, byte[]> plainBefore = new HashMap<>();
        for (var e : before.entrySet()) plainBefore.put(e.getKey(), VaultCrypto.open(made.vk(), e.getKey(), e.getValue()));
        assertFalse(before.isEmpty());

        Cloud.KeyChanged changed = rotate(desk);
        assertEquals(List.of(), changed.left(), "every file re-sealed");
        assertFalse(changed.resealFailed());
        byte[] vk = keyOf(desk);
        Map<String, byte[]> after = sealedFiles();
        assertEquals(before.keySet(), after.keySet());
        for (var e : after.entrySet()) {
            assertArrayEquals(plainBefore.get(e.getKey()), VaultCrypto.open(vk, e.getKey(), e.getValue()), e.getKey());
            assertFalse(opens(made.vk(), e.getKey(), e.getValue()), "the old key opens it no more: " + e.getKey());
        }
        assertNull(desk.round().error(), "read with the new key");
        assertEquals("DARK", desk.get("ui.theme"));
    }

    @Test
    void forgettingHereLeavesTheOthersAlone() throws Exception {
        laptop.set(VaultParts.DECLINED, "true");
        laptop.as(laptop.cloud.vault()::forgetHere);
        assertNull(slot(LAPTOP), "its slot gone");
        assertNotNull(slot(DESK), "the other's stays");
        for (String k : new String[] {VaultParts.ID, VaultParts.KEY, VaultParts.PRIVATE, VaultPeers.KEY, VaultMove.MOVED_AT}) assertNull(laptop.get(k), k);
        assertEquals("true", laptop.get(VaultParts.DECLINED), "declining is left as it was");
        assertEquals(Cloud.VaultState.DECLINED, laptop.round().vault(), "closed here, never removed: it can open again later");
        laptop.set(VaultParts.DECLINED, null);
        assertEquals(Cloud.VaultState.LOCKED, laptop.round().vault());
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
    }

    @Test
    void theWaysInWithASecretOfTheirOwnAreDroppedAndSaidSoAndTheCodeIsNew() throws Exception {
        Vault.addPassword(dav(), made.vk(), "una clave bien larga".toCharArray());
        Cloud.KeyChanged changed = rotate(desk);
        assertEquals(Set.of("PASSWORD"), changed.dropped(), "the password can't be wrapped again without it");
        assertFalse(changed.toString().contains(changed.code()), "nothing secret in its text form");
        List<VaultSlot> slots = Vault.slots(dav());
        assertTrue(slots.stream().noneMatch(s -> s.method() == VaultSlot.Method.PASSWORD));
        List<VaultSlot> recovery = slots.stream().filter(s -> s.method() == VaultSlot.Method.RECOVERY).toList();
        assertEquals(1, recovery.size(), "one recovery slot, the new one");
        assertEquals(1, recovery.getFirst().generation());
        assertArrayEquals(keyOf(desk), recovery.getFirst().unwrap(VaultSlot.recoveryKey(RecoveryCode.parse(changed.code()))));
        assertThrows(VaultCrypto.Broken.class, () -> recovery.getFirst().unwrap(VaultSlot.recoveryKey(made.code())), "the old code opens nothing");
    }

    @Test
    void aPublicKeyPutInByTheAccountGetsNoNewKey() throws Exception {
        VaultSlot was = slot(LAPTOP);
        ObjectNode params = was.params().deepCopy();
        params.put("x25519", Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded()));
        cloud.put(Vault.SLOTS + "/" + LAPTOP + ".json", new VaultSlot(was.id(), was.vault(), was.method(), was.label(), was.created(),
            was.generation(), params, was.wrapped()).toJson());
        rotate(desk);
        assertFalse(slot(LAPTOP).params().has("next"), "someone who can write to the account never gets the new key");
        assertEquals(Cloud.VaultState.REMOVED, laptop.round().vault());
    }

    @Test
    void whatAComputerSentWithTheOldKeyAfterTheChangeIsSealedAgainByIt() throws Exception {
        assertNull(laptop.write("texts/here/" + TEXT, "{}\n").round().error()); // sent, as any text
        rotate(desk);
        // The laptop's round had opened the vault with the old key just before, and wrote after the change: its own text,
        // and the secrets part as it agreed on it.
        SealedStorage old = new SealedStorage(dav(), () -> made.vk().clone());
        String text = "Unichat/Textos/" + LAPTOP + "/" + TEXT;
        old.mkdirs("Unichat/Textos/" + LAPTOP);
        old.put(text, "{}\n".getBytes(StandardCharsets.UTF_8));
        Bundle secrets = Bundle.of(LAPTOP, java.time.Instant.now(), Map.of(), null);
        assertEquals(laptop.get("cloud.base.secrets"), secrets.hash(), "what the laptop agreed on");
        old.put(VaultParts.SECRETS_FILE, secrets.toBytes());

        assertNull(laptop.round().error(), "never taken for damaged");
        assertTrue(opens(keyOf(laptop), text, cloud.files.get(Vault.ROOT + "/Textos/" + LAPTOP + "/" + TEXT)), "its text sealed again");
        assertTrue(opens(keyOf(laptop), VaultParts.SECRETS_FILE, cloud.files.get(Vault.ROOT + "/Ajustes/unichat-secrets.json")),
            "and the part it agreed on");
        assertNull(desk.round().error());
    }

    @Test
    void aPartSentWithTheOldKeyByAnotherIsNeverTakenIn() throws Exception {
        rotate(desk);
        // A computer removed before the change still has the old key and the account.
        new SealedStorage(dav(), () -> made.vk().clone()).put(VaultParts.SECRETS_FILE,
            Bundle.of(OTHER, java.time.Instant.now().plusSeconds(60), Map.of("gif.giphy.key", "falsa"), null).toBytes());
        Cloud.Status s = laptop.round();
        assertNull(s.error(), "a part that doesn't open never stops the round");
        assertEquals(new Cloud.Notice(List.of(Cloud.Part.SECRETS), List.of(), Map.of()), s.vaultNotice(), "said");
        assertNull(desk.round().error());
        assertFalse("falsa".equals(laptop.get("gif.giphy.key")), "no computer of the vault takes it in");
        assertFalse("falsa".equals(desk.get("gif.giphy.key")));
        byte[] now = VaultCrypto.open(keyOf(laptop), VaultParts.SECRETS_FILE, cloud.files.get(Vault.ROOT + "/Ajustes/unichat-secrets.json"));
        assertEquals(LAPTOP, Bundle.parse(now).machine(), "this computer's own copy went up in its place, with the new key");
        assertFalse(Bundle.parse(now).settings().containsKey("gif.giphy.key"));
    }

    @Test
    void aPartSealedAtANewerGenerationIsNeverOverwrittenWithAnOlderKey() throws Exception {
        String stored = Vault.ROOT + "/Ajustes/unichat-secrets.json";
        try (VaultParts.Round old = laptop.as(() -> java.util.concurrent.CompletableFuture.completedFuture(
            VaultParts.open(dav(), (p, b) -> false, () -> { })))) {
            rotate(desk); // meanwhile: every part sealed again at the next generation
            byte[] sealedNow = cloud.files.get(stored);
            Storage personal = old.personal(dav());
            assertThrows(SealedStorage.Damaged.class, () -> personal.get(VaultParts.SECRETS_FILE), "fails as before");
            assertArrayEquals(sealedNow, cloud.files.get(stored), "and nothing written over it");
        }
    }

    @Test
    void anotherComputersTextThatDoesntOpenIsLeftAndNamedAndTheRoundGoesOn() throws Exception {
        String folder = Vault.ROOT + "/Textos/" + OTHER;
        cloud.dirs.add(folder);
        cloud.put(folder + "/index.json", "dañado".getBytes(StandardCharsets.UTF_8));
        int before = cloud.puts.get();
        Cloud.Status s = desk.round();
        assertNull(s.error());
        assertEquals(Map.of(OTHER, ""), s.vaultNotice().textsDamaged(), "named: " + s.vaultNotice());
        assertArrayEquals("dañado".getBytes(StandardCharsets.UTF_8), cloud.files.get(folder + "/index.json"), "never written over");
        assertTrue(cloud.puts.get() >= before);
    }

    @Test
    void aTextForgedInAComputersFolderWithTheOldKeyIsLeftAndNeverSpread() throws Exception {
        String mine = "{\"mio\":12345}\n", forged = "{\"forjado\":1}\n"; // the same size: only what it says differs
        assertNull(laptop.write("texts/here/" + TEXT, mine).round().error());
        assertNull(desk.round().error());
        rotate(desk);
        SealedStorage old = new SealedStorage(dav(), () -> made.vk().clone());
        String folder = "Unichat/Textos/" + LAPTOP;
        old.put(folder + "/" + TEXT, forged.getBytes(StandardCharsets.UTF_8));
        old.put(folder + "/index.json", TextIndex.write(Map.of(TEXT, (long) forged.length())));
        assertNull(laptop.round().error());
        assertEquals(mine, new String(VaultCrypto.open(keyOf(laptop), folder + "/" + TEXT,
            cloud.files.get(Vault.ROOT + "/Textos/" + LAPTOP + "/" + TEXT)), StandardCharsets.UTF_8),
            "not what this computer has: never sealed again, its own sent in its place");
        assertTrue(opens(keyOf(laptop), folder + "/index.json", cloud.files.get(Vault.ROOT + "/Textos/" + LAPTOP + "/index.json")),
            "its index sent anew");
        desk.round();
        assertEquals(mine, desk.read("texts/" + LAPTOP + "/" + TEXT), "and no other computer takes the forged one in");
    }

    @Test
    void aSlotGoneAndPutBackWithAnotherKeyIsRefused() throws Exception {
        goneAndPutBackWithAnotherKey();
        Cloud.KeyChanged changed = rotate(desk);
        assertEquals(Map.of(LAPTOP, "Portátil"), changed.refused(), "pinned as first seen, though its slot was gone a while");
        assertFalse(slot(LAPTOP).params().has("next"));
    }

    @Test
    void aKeyAcceptedByThePersonIsOfferedTheNext() throws Exception {
        goneAndPutBackWithAnotherKey();
        desk.as(() -> desk.cloud.vault().acceptKey(LAPTOP)); // the person says it's the laptop's, set up again
        assertEquals(Map.of(), rotate(desk).refused());
        assertTrue(slot(LAPTOP).params().has("next"), "offered now");
    }

    private void goneAndPutBackWithAnotherKey() throws Exception {
        cloud.files.remove(Vault.SLOTS + "/" + LAPTOP + ".json");
        assertNull(desk.round().error());
        laptopSlotWithKey(KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded());
        assertNull(desk.round().error());
    }

    @Test
    void aComputerRemovedHereIsNeverOfferedAgainEvenPutBack() throws Exception {
        VaultSlot laptopSlot = slot(LAPTOP);
        desk.as(() -> desk.cloud.vault().removeComputer(LAPTOP));
        cloud.put(Vault.SLOTS + "/" + LAPTOP + ".json", laptopSlot.toJson()); // put back as it was, by whoever kept a copy
        assertNull(desk.round().error());
        Cloud.KeyChanged changed = rotate(desk);
        assertEquals(Map.of(LAPTOP, "Portátil"), changed.refused());
        assertFalse(slot(LAPTOP).params().has("next"));
    }

    /** {@link #LAPTOP}'s slot written anew with {@code publicKey} sealed with the vault key: what one holding it can do. */
    private void laptopSlotWithKey(byte[] publicKey) throws Exception {
        ObjectNode params = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        VaultNext.bind(params, made.vk(), made.info().id(), LAPTOP, publicKey);
        VaultSlot slot = VaultSlot.wrap(made.info().id(), LAPTOP, VaultSlot.Method.COMPUTER, "Portátil", 0, VaultCrypto.randomKey(), params, made.vk());
        cloud.put(Vault.SLOTS + "/" + LAPTOP + ".json", slot.toJson());
    }

    @Test
    void aKeyPutInByOneHoldingTheVaultKeyIsRefusedAndNamed() throws Exception {
        tamper(LAPTOP);
        Cloud.KeyChanged changed = rotate(desk);
        assertEquals(Map.of(LAPTOP, "Portátil"), changed.refused(), "its key isn't the one this computer saw: said, by name");
        assertFalse(slot(LAPTOP).params().has("next"), "the one who put it in is offered nothing");
    }

    @Test
    void aComputerPutsBackItsOwnKeyAndIsOfferedTheNext() throws Exception {
        tamper(LAPTOP);
        assertNull(laptop.round().error());
        assertArrayEquals(laptopWay.pair().getPublic().getEncoded(),
            Base64.getDecoder().decode(slot(LAPTOP).params().get("x25519").asText()), "its own key back in its slot");
        Cloud.KeyChanged changed = rotate(desk);
        assertEquals(Map.of(), changed.refused());
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        assertArrayEquals(keyOf(desk), keyOf(laptop));
    }

    /** {@code id}'s slot given another public key, sealed with the vault key: what a computer removed but not yet shut out can do. */
    private void tamper(String id) throws Exception {
        VaultSlot was = slot(id);
        ObjectNode params = was.params().deepCopy();
        VaultNext.bind(params, made.vk(), was.vault(), id, KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded());
        cloud.put(Vault.SLOTS + "/" + id + ".json", new VaultSlot(was.id(), was.vault(), was.method(), was.label(), was.created(),
            was.generation(), params, was.wrapped()).toJson());
    }

    @Test
    void aKeyThatIsntOneGetsNoOfferAndTheChangeGoesOn() throws Exception {
        byte[] lowOrder = java.security.KeyFactory.getInstance("X25519").generatePublic(
            new java.security.spec.XECPublicKeySpec(java.security.spec.NamedParameterSpec.X25519, java.math.BigInteger.ZERO)).getEncoded();
        ObjectNode params = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        VaultNext.bind(params, made.vk(), made.info().id(), OTHER, lowOrder);
        cloud.put(Vault.SLOTS + "/" + OTHER + ".json", VaultSlot.wrap(made.info().id(), OTHER, VaultSlot.Method.COMPUTER, "Raro", 0,
            VaultCrypto.randomKey(), params, made.vk()).toJson());
        Cloud.KeyChanged changed = rotate(desk);
        assertEquals(Map.of(OTHER, "Raro"), changed.refused());
        assertEquals(1, Vault.info(dav()).generation(), "the change went on for the others");
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
    }

    @Test
    void aStrayEntryInTheVaultNeverHoldsTheChangeBack() throws Exception {
        cloud.filesAreNoFolders = true; // as Dropbox answers a listing of a file (path/not_folder)
        cloud.put(Vault.ROOT + "/raro", "x".getBytes(StandardCharsets.UTF_8));
        Cloud.KeyChanged changed = rotate(desk);
        assertTrue(changed.resealFailed(), "said");
        Cloud.Status s = laptop.round();
        assertEquals(Cloud.VaultState.OPEN, s.vault());
        assertNull(s.error());
        assertEquals(1, slot(LAPTOP).generation(), "it took the new key all the same");
        assertNull(desk.round().error());
    }

    @Test
    void aComputersPublicKeyIsTheOneItsPrivateKeyMakes() {
        assertArrayEquals(laptopWay.pair().getPublic().getEncoded(), VaultNext.publicOf(laptopWay.pair().getPrivate().getEncoded()));
        assertNull(VaultNext.publicOf(new byte[] {1, 2, 3}));
    }

    @Test
    void aFileThatDoesntOpenWithTheOldKeyIsLeftAndSaid() throws Exception {
        String stored = Vault.ROOT + "/Ajustes/unichat-otro.json";
        cloud.put(stored, "no es nuestro".getBytes(StandardCharsets.UTF_8));
        Cloud.KeyChanged changed = rotate(desk);
        assertEquals(List.of(CloudAccount.ROOT + "/Ajustes/unichat-otro.json"), changed.left());
        assertArrayEquals("no es nuestro".getBytes(StandardCharsets.UTF_8), cloud.files.get(stored), "left as it was");
    }

    @Test
    void aChangeCutBeforeTheGenerationRoseLeavesEveryoneOnTheOldKey() throws Exception {
        cloud.failOnce.add(Vault.INFO);
        assertThrows(ExecutionException.class, () -> rotate(desk));
        assertEquals(0, Vault.info(dav()).generation());
        assertTrue(Vault.slots(dav()).stream().noneMatch(s -> s.params().has("next")), "its offers taken back");
        assertEquals(1, Vault.slots(dav()).stream().filter(s -> s.method() == VaultSlot.Method.RECOVERY).count(), "and its new code");
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        assertNull(desk.round().error());
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        assertNull(laptop.round().error());
        assertArrayEquals(made.vk(), keyOf(laptop));
        assertTrue(Vault.slots(dav()).stream().anyMatch(s -> s.method() == VaultSlot.Method.RECOVERY
            && opensSlot(s, VaultSlot.recoveryKey(made.code()))), "the old code still opens it");
    }

    private static boolean opensSlot(VaultSlot s, byte[] key) {
        try {
            s.unwrap(key);
            return true;
        } catch (VaultCrypto.Broken e) {
            return false;
        }
    }

    @Test
    void aChangeWhoseGenerationRoseWithItsAnswerLostIsDone() throws Exception {
        cloud.failAfterOnce.add(Vault.INFO);
        Cloud.KeyChanged changed = rotate(desk);
        assertNotNull(changed.code(), "the new code is shown");
        assertEquals(1, Vault.info(dav()).generation());
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault());
        assertArrayEquals(keyOf(desk), keyOf(laptop));
    }

    @Test
    void twoChangesWhileAComputerIsOffAndItStillGoesOn() throws Exception {
        rotate(desk);
        rotate(desk);
        assertEquals(2, Vault.info(dav()).generation());
        Cloud.Status s = laptop.round();
        assertNull(s.error());
        assertEquals(Cloud.VaultState.OPEN, s.vault());
        assertArrayEquals(keyOf(desk), keyOf(laptop));
    }

    @Test
    void aComputerThatWentOnChangesTheKeyForTheOneThatChangedItFirst() throws Exception {
        rotate(desk);
        assertNull(laptop.round().error());
        rotate(laptop);
        assertEquals(2, Vault.info(dav()).generation());
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        assertArrayEquals(keyOf(laptop), keyOf(desk));
    }

    @Test
    void thisComputerIsntRemovedThatWayNorAnythingThatIsntAMachine() throws Exception {
        ExecutionException own = assertThrows(ExecutionException.class, () -> desk.as(() -> desk.cloud.vault().removeComputer(DESK)));
        assertTrue(own.getCause() instanceof IllegalArgumentException);
        ExecutionException bad = assertThrows(ExecutionException.class, () -> desk.as(() -> desk.cloud.vault().removeComputer("../boveda")));
        assertTrue(bad.getCause() instanceof IllegalArgumentException);
        String recovery = Vault.slots(dav()).stream().filter(s -> s.method() == VaultSlot.Method.RECOVERY).findFirst().orElseThrow().id();
        desk.as(() -> desk.cloud.vault().removeComputer(recovery));
        assertNotNull(slot(recovery), "only a computer's slot goes this way");
        assertNotNull(slot(DESK));
        desk.as(() -> desk.cloud.vault().removeComputer(OTHER)); // none such: nothing
    }

    @Test
    void theKeyIsChangedOnlyWithTheVaultOpenHere() throws Exception {
        VaultComputer other = new VaultComputer(cloud, dir, OTHER);
        int puts = cloud.puts.get();
        assertThrows(ExecutionException.class, () -> rotate(other));
        assertEquals(puts, cloud.puts.get());
        assertEquals(0, Vault.info(dav()).generation());
    }

    @Test
    void aWrongKeyChangesNothing() throws Exception {
        assertThrows(java.io.IOException.class, () -> Vault.rotate(dav(), VaultCrypto.randomKey(), DESK, VaultPeers.load(made.info().id())));
        assertEquals(0, Vault.info(dav()).generation());
        assertFalse(slot(LAPTOP).params().has("next"));
    }

    @Test
    void aFileSentAnewWhileItsSealedAgainIsLeftForWhoSentIt() throws Exception {
        String logical = VaultParts.PINNED_FILE, stored = Vault.ROOT + "/Ajustes/unichat-pinned.json";
        new SealedStorage(dav(), () -> made.vk().clone()).put(logical, "viejo".getBytes(StandardCharsets.UTF_8));
        byte[] newVk = VaultCrypto.randomKey(), sentMeanwhile = VaultCrypto.seal(newVk, logical, "nuevo".getBytes(StandardCharsets.UTF_8));
        Storage racing = (Storage) java.lang.reflect.Proxy.newProxyInstance(
            Storage.class.getClassLoader(), new Class<?>[] {Storage.class}, (proxy, m, args) -> {
                Object out = m.invoke(dav(), args);
                // Another computer sends its copy right after this one was read.
                if (m.getName().equals("get") && stored.equals(args[0])) cloud.put(stored, sentMeanwhile);
                return out;
            });
        VaultReseal.run(racing, made.vk(), newVk);
        assertArrayEquals(sentMeanwhile, cloud.files.get(stored), "the newer copy is never written over");
    }

    @Test
    void aForgedTextNeverHoldsBackTheNextOnesAndItsWriterSendsItsOwnAgain() throws Exception {
        String first = "3f2a9c1b2d4e 2026-10.jsonl", second = TEXT; // October sorts first
        String real = "{\"mio\":12345}\n", forged = "{\"forjado\":1}\n"; // the same size
        assertNull(laptop.write("texts/here/" + first, "{}\n").write("texts/here/" + second, "{}\n").round().error());
        assertNull(desk.round().error());
        assertNull(laptop.write("texts/here/" + first, "{}\n" + real).write("texts/here/" + second, "{}\n{\"b\":1}\n").round().error());
        rotate(desk);
        new SealedStorage(dav(), () -> made.vk().clone()).put("Unichat/Textos/" + LAPTOP + "/" + first,
            ("{}\n" + forged).getBytes(StandardCharsets.UTF_8));

        Cloud.Status s = desk.round();
        assertNull(s.error());
        assertEquals("{}\n{\"b\":1}\n", desk.read("texts/" + LAPTOP + "/" + second), "the one after it still comes");
        assertEquals("{}\n", desk.read("texts/" + LAPTOP + "/" + first), "the forged one never");
        assertEquals(Map.of(LAPTOP, "Portátil"), s.vaultNotice().textsDamaged(), "named by its computer's name: " + s.vaultNotice());

        assertNull(laptop.round().error());
        assertNull(desk.round().error());
        assertEquals("{}\n" + real, desk.read("texts/" + LAPTOP + "/" + first), "its writer sent its own again in its place");
    }

    @Test
    void aKeyThatIsntAComputersOrTheVaultsIsNeverAccepted() throws Exception {
        String recovery = Vault.slots(dav()).stream().filter(v -> v.method() == VaultSlot.Method.RECOVERY).findFirst().orElseThrow().id();
        tamperWithoutTheVaultKey(LAPTOP);
        for (String id : new String[] {OTHER, recovery, LAPTOP}) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> desk.as(() -> desk.cloud.vault().acceptKey(id)), id);
            assertEquals(app.unichat.support.I18n.t("cloud.error.vaultNoSuchKey"), e.getCause().getMessage(), id);
        }
    }

    /** {@code id}'s slot given another public key without the vault key's seal: what someone with only the account can do. */
    private void tamperWithoutTheVaultKey(String id) throws Exception {
        VaultSlot was = slot(id);
        ObjectNode params = was.params().deepCopy();
        params.put("x25519", Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded()));
        cloud.put(Vault.SLOTS + "/" + id + ".json", new VaultSlot(was.id(), was.vault(), was.method(), was.label(), was.created(),
            was.generation(), params, was.wrapped()).toJson());
    }

    @Test
    void aComputerIsKeptOutThoughItsSlotCouldntBeDeleted() throws Exception {
        cloud.failDeleteOnce.add(Vault.SLOTS + "/" + LAPTOP + ".json");
        assertThrows(ExecutionException.class, () -> desk.as(() -> desk.cloud.vault().removeComputer(LAPTOP)));
        assertNotNull(slot(LAPTOP), "still there");
        assertEquals(Map.of(LAPTOP, "Portátil"), rotate(desk).refused(), "but never offered the next key");
    }

    @Test
    void aPartThatDoesntOpenAndHasNothingOfOursToReplaceItIsSaidDamaged() throws Exception {
        String stored = Vault.ROOT + "/Ajustes/unichat-transcripts.json";
        cloud.put(stored, "dañado".getBytes(StandardCharsets.UTF_8));
        Cloud.Status s = desk.round(); // no transcripts here: nothing goes up in its place
        assertArrayEquals("dañado".getBytes(StandardCharsets.UTF_8), cloud.files.get(stored));
        assertEquals(new Cloud.Notice(List.of(), List.of(Cloud.Part.TRANSCRIPTS), Map.of()), s.vaultNotice());
    }

    @Test
    void anIndexForgedInAComputersFolderWithTheOldKeyIsSentAnewByIt() throws Exception {
        assertNull(laptop.write("texts/here/" + TEXT, "{}\n").round().error());
        rotate(desk);
        String index = "Unichat/Textos/" + LAPTOP + "/index.json";
        new SealedStorage(dav(), () -> made.vk().clone()).put(index, TextIndex.write(Map.of(TEXT, 99L)));
        assertNull(laptop.round().error());
        assertEquals(Map.of(TEXT, 3L), TextIndex.read(VaultCrypto.open(keyOf(laptop), index, cloud.files.get(Vault.ROOT + "/Textos/" + LAPTOP + "/index.json"))),
            "never sealed again: what it has, sent anew");
    }

    /** {@code boveda.json} rewritten at {@code generation} by someone with only the account: nothing vouches for it. */
    private void raiseByHand(long generation) throws Exception {
        cloud.put(Vault.INFO, ("{\"id\":\"" + made.info().id() + "\",\"created\":\"" + made.info().created()
            + "\",\"generation\":" + generation + "}").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anOfferForgedThroughTheAccountIsNeverTaken() throws Exception {
        byte[] theirs = VaultCrypto.randomKey();
        VaultSlot was = slot(LAPTOP);
        ObjectNode params = was.params().deepCopy();
        // Someone with only the account: an agreement with the slot's own public key, a key of their own, the generation raised.
        VaultNext.offer(params, Base64.getDecoder().decode(params.get("x25519").asText()), was.vault(), LAPTOP, 1, theirs, VaultCrypto.randomKey());
        cloud.put(Vault.SLOTS + "/" + LAPTOP + ".json", new VaultSlot(was.id(), was.vault(), was.method(), was.label(), was.created(),
            was.generation(), params, was.wrapped()).toJson());
        raiseByHand(1);
        int puts = cloud.puts.get();
        Cloud.Status s = laptop.write("texts/here/" + TEXT, "{}\n{\"más\":1}\n").round();
        assertEquals(Cloud.VaultState.UNRECOGNIZED, s.vault(), "their key is never taken");
        assertEquals(puts, cloud.puts.get(), "nothing sealed with it, nothing sent");
        sealedFiles().forEach((p, d) -> assertFalse(opens(theirs, p, d), "nothing opens with their key: " + p));
        assertEquals(0, slot(LAPTOP).generation(), "its slot left on the key it had");
    }

    @Test
    void aGenerationRaisedByHandLocksNobodyOut() throws Exception {
        raiseByHand(1);
        int puts = cloud.puts.get();
        Cloud.Status s = laptop.write("texts/here/" + TEXT, "{}\n{\"más\":1}\n").set("ui.theme", "SEPIA").round();
        assertEquals(Cloud.VaultState.UNRECOGNIZED, s.vault(), "never removed for a change nobody vouches for");
        assertEquals(puts, cloud.puts.get(), "it sends nothing new");
        assertNotNull(laptop.get(VaultParts.KEY), "and keeps its key");
        laptop.as(() -> laptop.cloud.vault().openVault(VaultView.Way.RECOVERY, made.code().shown().toCharArray()));
        assertEquals(Cloud.VaultState.OPEN, laptop.round().vault(), "the recovery code still opens it");
        assertArrayEquals(made.vk(), keyOf(laptop), "on the key it always had");
    }

    @Test
    void aComputerLeftOutOfARealChangeIsRemoved() throws Exception {
        VaultComputer third = new VaultComputer(cloud, dir, OTHER).opened(made.info(), Vault.addComputer(dav(), made.vk(), OTHER, "Tercero"));
        assertNull(third.round().error());
        assertNull(desk.round().error()); // pinned here
        desk.as(() -> desk.cloud.vault().rotateKey(Set.of(OTHER))); // the laptop not confirmed: offered nothing
        assertEquals(Cloud.VaultState.REMOVED, laptop.round().vault(), "the third one's offer says the change is real");
        assertEquals(Cloud.VaultState.OPEN, third.round().vault());
    }

    @Test
    void anOfferForgedOnTopOfARealOneIsNeverTaken() throws Exception {
        rotate(desk); // the laptop, off, is offered generation 1
        byte[] theirs = VaultCrypto.randomKey();
        VaultSlot was = slot(LAPTOP);
        ObjectNode params = was.params().deepCopy();
        // Kept behind theirs as an offer not yet taken is, vouched for with a key they don't have.
        VaultNext.offer(params, Base64.getDecoder().decode(params.get("x25519").asText()), was.vault(), LAPTOP, 2, theirs, VaultCrypto.randomKey());
        cloud.put(Vault.SLOTS + "/" + LAPTOP + ".json", new VaultSlot(was.id(), was.vault(), was.method(), was.label(), was.created(),
            was.generation(), params, was.wrapped()).toJson());
        raiseByHand(2);
        assertEquals(Cloud.VaultState.UNRECOGNIZED, laptop.round().vault());
        assertEquals(0, slot(LAPTOP).generation(), "nothing taken");
    }
}

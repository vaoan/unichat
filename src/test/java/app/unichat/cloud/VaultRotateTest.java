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
        return c.as(c.cloud::rotateKey);
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
        desk.as(() -> desk.cloud.removeComputer(LAPTOP));
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
        laptop.as(laptop.cloud::forgetHere);
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
        laptop.round();
        desk.round();
        assertFalse("falsa".equals(laptop.get("gif.giphy.key")), "no computer of the vault takes it in");
        assertFalse("falsa".equals(desk.get("gif.giphy.key")));
        assertFalse(opens(keyOf(laptop), VaultParts.SECRETS_FILE, cloud.files.get(Vault.ROOT + "/Ajustes/unichat-secrets.json")),
            "nor seals it with the new key");
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
        ExecutionException own = assertThrows(ExecutionException.class, () -> desk.as(() -> desk.cloud.removeComputer(DESK)));
        assertTrue(own.getCause() instanceof IllegalArgumentException);
        ExecutionException bad = assertThrows(ExecutionException.class, () -> desk.as(() -> desk.cloud.removeComputer("../boveda")));
        assertTrue(bad.getCause() instanceof IllegalArgumentException);
        String recovery = Vault.slots(dav()).stream().filter(s -> s.method() == VaultSlot.Method.RECOVERY).findFirst().orElseThrow().id();
        desk.as(() -> desk.cloud.removeComputer(recovery));
        assertNotNull(slot(recovery), "only a computer's slot goes this way");
        assertNotNull(slot(DESK));
        desk.as(() -> desk.cloud.removeComputer(OTHER)); // none such: nothing
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
}

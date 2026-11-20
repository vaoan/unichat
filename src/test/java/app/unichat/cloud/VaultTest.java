package app.unichat.cloud;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VaultTest {
    private static final String MAC = UUID.randomUUID().toString(), PC = UUID.randomUUID().toString();

    private FakeDav cloud;
    private Storage storage;

    @BeforeEach
    void account() throws IOException {
        cloud = new FakeDav("u", "p");
        storage = new WebDav(cloud.url(), "u", "p");
    }

    @AfterEach
    void close() {
        cloud.close();
    }

    @Test
    void leftoversOfADeletedVaultAreClearedBeforeANewOne() throws Exception {
        Vault.Created old = Vault.create(storage, MAC, "Escritorio");
        new SealedStorage(storage, () -> old.vk().clone()).put(TextSync.ROOT + "/" + MAC + "/3f2a9c1b2d4e 2026-11.jsonl", new byte[] {1});
        storage.delete(Vault.INFO); // its folder couldn't go when it was turned off
        Vault.Created made = Vault.create(storage, MAC, "Escritorio");
        assertEquals(java.util.Set.of(Vault.INFO), new java.util.TreeSet<>(cloud.files.keySet().stream()
            .filter(p -> !p.startsWith(Vault.SLOTS + "/")).toList()), "nothing of the old one left");
        assertEquals(2, cloud.files.keySet().stream().filter(p -> p.startsWith(Vault.SLOTS + "/")).count(), "the new one's slots only");
        java.util.Arrays.fill(old.vk(), (byte) 0);
        java.util.Arrays.fill(made.vk(), (byte) 0);
    }

    @Test
    void aVaultIsMadeWithItsRecoveryAndThisComputer() throws Exception {
        assertNull(Vault.info(storage), "no vault yet");
        Vault.Created made = Vault.create(storage, MAC, "Mac de Ana");

        Vault.Info info = Vault.info(storage);
        assertEquals(made.info(), info);
        assertEquals(0, info.generation());
        assertEquals(0, Vault.generation(storage));
        assertEquals(32, made.vk().length);

        Map<VaultSlot.Method, VaultSlot> slots = byMethod(Vault.slots(storage));
        assertEquals(Set.of(VaultSlot.Method.RECOVERY, VaultSlot.Method.COMPUTER), slots.keySet());
        VaultSlot computer = slots.get(VaultSlot.Method.COMPUTER);
        assertEquals(MAC, computer.id(), "one slot per computer, under its machine's id");
        assertEquals("Mac de Ana", computer.label());
        assertTrue(cloud.files.containsKey(Vault.SLOTS + "/" + MAC + ".json"));
        assertTrue(cloud.files.containsKey(Vault.SLOTS + "/" + slots.get(VaultSlot.Method.RECOVERY).id() + ".json"));

        byte[] published = Base64.getDecoder().decode(computer.params().get("x25519").asText());
        assertArrayEquals(made.computer().pair().getPublic().getEncoded(), published, "its X25519 public key, for a new key later");
        assertEquals(made.computer().pair().getPublic(),
            KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(published)));
        assertFalse(made.toString().contains(made.code().shown()), "nothing secret in its text form");
    }

    @Test
    void anyWayInOpensTheSameKey() throws Exception {
        Vault.Created made = Vault.create(storage, MAC, "Mac");
        char[] pw = "una clave muy larga".toCharArray();
        VaultSlot password = Vault.addPassword(storage, made.vk(), pw);
        Map<VaultSlot.Method, VaultSlot> slots = byMethod(Vault.slots(storage));

        assertArrayEquals(made.vk(), Vault.open(slots.get(VaultSlot.Method.RECOVERY), VaultSlot.recoveryKey(made.code())));
        assertArrayEquals(made.vk(), Vault.open(slots.get(VaultSlot.Method.COMPUTER), made.computer().key()));
        VaultSlot stored = slots.get(VaultSlot.Method.PASSWORD);
        assertEquals(password, stored);
        assertArrayEquals(made.vk(), Vault.open(stored, VaultSlot.passwordKey(pw, stored.params())));
        RecoveryCode typed = RecoveryCode.parse(made.code().shown().toLowerCase(java.util.Locale.ROOT));
        assertArrayEquals(made.vk(), Vault.open(slots.get(VaultSlot.Method.RECOVERY), VaultSlot.recoveryKey(typed)));

        assertThrows(VaultCrypto.Broken.class,
            () -> Vault.open(slots.get(VaultSlot.Method.COMPUTER), VaultCrypto.randomKey()));
        assertThrows(VaultCrypto.Broken.class,
            () -> Vault.open(stored, VaultSlot.passwordKey("otra clave muy larga".toCharArray(), stored.params())));
    }

    @Test
    void twoComputersMakingOneAtOnceKeepTheFirst() throws Exception {
        Vault.Created first = Vault.create(storage, MAC, "Mac");
        Map<String, byte[]> before = Map.copyOf(cloud.files);

        assertThrows(Vault.Exists.class, () -> Vault.create(storage, PC, "PC"));
        assertEquals(before.keySet(), cloud.files.keySet(), "nothing written");
        before.forEach((path, bytes) -> assertArrayEquals(bytes, cloud.files.get(path), path));
        assertEquals(first.info(), Vault.info(storage));
    }

    @Test
    void aVaultMadeElsewhereWhileThisOneWasBeingWrittenWins() throws Exception {
        // The other computer's INFO lands right after ours: whoever reads back someone else's id stops.
        byte[] theirs = ("{\"id\":\"" + UUID.randomUUID() + "\",\"created\":\"2026-11-19T10:00:00Z\",\"generation\":0}")
            .getBytes(StandardCharsets.UTF_8);
        Storage racing = new Racing(storage, path -> {
            if (path.equals(Vault.INFO)) cloud.put(Vault.INFO, theirs);
        }, NOTHING);

        assertThrows(Vault.Exists.class, () -> Vault.create(racing, MAC, "Mac"));
        assertArrayEquals(theirs, cloud.files.get(Vault.INFO), "the winner's vault untouched");
        assertTrue(Vault.slots(storage).isEmpty(), "the loser wrote no slot");
    }

    @Test
    void aVaultMadeElsewhereAfterOursWasReadBackStillWins() throws Exception {
        // Both found no vault; ours was read back as ours, then theirs overwrote it while our slots went up.
        String other = UUID.randomUUID().toString();
        byte[] theirs = ("{\"id\":\"" + other + "\",\"created\":\"2026-11-19T10:00:00Z\",\"generation\":0}")
            .getBytes(StandardCharsets.UTF_8);
        VaultSlot theirSlot = VaultSlot.wrap(other, VaultSlot.Method.RECOVERY, "recovery", 0, VaultCrypto.randomKey(),
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(), VaultCrypto.randomKey());
        String theirPath = Vault.SLOTS + "/" + theirSlot.id() + ".json";
        Storage racing = new Racing(storage, path -> {
            if (path.startsWith(Vault.SLOTS + "/") && !cloud.files.containsKey(theirPath)) {
                cloud.put(Vault.INFO, theirs);
                cloud.put(theirPath, theirSlot.toJson());
            }
        }, NOTHING);

        assertThrows(Vault.Exists.class, () -> Vault.create(racing, MAC, "Mac"));
        assertArrayEquals(theirs, cloud.files.get(Vault.INFO), "their vault stays");
        assertEquals(Set.of(theirPath), slotFiles(), "ours are gone, theirs is intact");
        assertEquals(List.of(theirSlot), Vault.slots(storage));
    }

    @Test
    void aFailureHalfwayLeavesNoVaultBehind() throws Exception {
        java.util.concurrent.atomic.AtomicInteger slotsPut = new java.util.concurrent.atomic.AtomicInteger();
        Storage failing = new Racing(storage, path -> {
            if (path.startsWith(Vault.SLOTS + "/") && slotsPut.incrementAndGet() == 2) throw new IOException("the network dropped");
        }, NOTHING);

        assertThrows(IOException.class, () -> Vault.create(failing, MAC, "Mac"));
        assertNull(Vault.info(storage), "no half-made vault blocks the account");
        assertEquals(Set.of(), slotFiles());
        Vault.Created made = Vault.create(storage, MAC, "Mac");
        assertEquals(2, Vault.slots(storage).size());
        assertArrayEquals(made.vk(), Vault.open(byMethod(Vault.slots(storage)).get(VaultSlot.Method.RECOVERY),
            VaultSlot.recoveryKey(made.code())));
    }

    @Test
    void anAnswerLostAfterTheVaultFileLandedLeavesNoVaultBehind() throws Exception {
        Storage lost = new Racing(storage, path -> {
            if (path.equals(Vault.INFO)) throw new IOException("the answer never came");
        }, NOTHING);
        assertThrows(IOException.class, () -> Vault.create(lost, MAC, "Mac"));
        assertFalse(cloud.files.containsKey(Vault.INFO), "the file that landed is taken back");
        Vault.create(storage, MAC, "Mac");
        assertNotNull(Vault.info(storage));
    }

    @Test
    void aReadBackThatFailsLeavesNoVaultBehind() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        Storage flaky = new Racing(storage, NOTHING, NOTHING, path -> {
            if (path.equals(Vault.INFO) && failed.compareAndSet(false, true)) throw new IOException("the network dropped");
        });
        assertThrows(IOException.class, () -> Vault.create(flaky, MAC, "Mac"));
        assertTrue(failed.get());
        assertFalse(cloud.files.containsKey(Vault.INFO));
        assertNull(Vault.info(storage));
        Vault.create(storage, MAC, "Mac");
        assertEquals(2, Vault.slots(storage).size());
    }

    @Test
    void aVaultFileThatIsntOneIsReplacedOnlyWhenNoSlotIsThere() throws Exception {
        cloud.put(Vault.INFO, "{garbage".getBytes(StandardCharsets.UTF_8));
        Vault.Created made = Vault.create(storage, MAC, "Mac");
        assertEquals(made.info(), Vault.info(storage), "nothing anyone could open was there");

        cloud.put(Vault.INFO, "{garbage".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> before = Map.copyOf(cloud.files);
        assertThrows(Vault.Exists.class, () -> Vault.create(storage, PC, "PC"), "slots are there: never replaced");
        assertEquals(before.keySet(), cloud.files.keySet());
        assertArrayEquals("{garbage".getBytes(StandardCharsets.UTF_8), cloud.files.get(Vault.INFO));
    }

    @Test
    void aVaultIdThatIsntOursReadsAsNone() throws Exception {
        cloud.put(Vault.INFO, "{\"id\":\"../x\",\"created\":\"2026-11-19T10:00:00Z\",\"generation\":0}".getBytes(StandardCharsets.UTF_8));
        assertNull(Vault.info(storage));
        String good = UUID.randomUUID().toString();
        cloud.put(Vault.INFO, ("{\"id\":\"" + good + "\",\"created\":\"2026-11-19T10:00:00Z\",\"generation\":0}").getBytes(StandardCharsets.UTF_8));
        assertEquals(good, Vault.info(storage).id());
    }

    @Test
    void aSlotOfAnotherVaultIsntListed() throws Exception {
        Vault.create(storage, MAC, "Mac");
        VaultSlot stranger = VaultSlot.wrap(UUID.randomUUID().toString(), VaultSlot.Method.PASSWORD, "password", 0,
            VaultCrypto.randomKey(), VaultSlot.newPasswordParams(), VaultCrypto.randomKey());
        cloud.put(Vault.SLOTS + "/" + stranger.id() + ".json", stranger.toJson());
        assertEquals(Set.of(VaultSlot.Method.RECOVERY, VaultSlot.Method.COMPUTER), byMethod(Vault.slots(storage)).keySet());
    }

    @Test
    void aSlotWhoseMethodWasChangedDoesntOpen() throws Exception {
        Vault.Created made = Vault.create(storage, MAC, "Mac");
        String path = Vault.SLOTS + "/" + MAC + ".json";
        cloud.put(path, new String(cloud.files.get(path), StandardCharsets.UTF_8).replace("\"COMPUTER\"", "\"PASSKEY\"")
            .getBytes(StandardCharsets.UTF_8));
        VaultSlot changed = byMethod(Vault.slots(storage)).get(VaultSlot.Method.PASSKEY);
        assertThrows(VaultCrypto.Broken.class, () -> Vault.open(changed, made.computer().key()));
    }

    @Test
    void aNewCodeIsGivenEvenWhenTheOldOneCantBeDeleted() throws Exception {
        Vault.Created made = Vault.create(storage, MAC, "Mac");
        Storage stuck = new Racing(storage, NOTHING, path -> {
            throw new IOException("the network dropped");
        });
        RecoveryCode fresh = Vault.newRecovery(stuck, made.vk());
        VaultSlot newest = recoveries().stream().filter(s -> opens(s, fresh, made.vk())).findFirst().orElseThrow();
        assertEquals(2, recoveries().size(), "the old one is still there");

        RecoveryCode third = Vault.newRecovery(storage, made.vk());
        assertEquals(1, recoveries().size(), "the next one prunes every other");
        assertTrue(opens(recoveries().getFirst(), third, made.vk()));
        assertFalse(recoveries().contains(newest));
    }

    @Test
    void aPasswordIsCountedInCharactersNotChars() throws Exception {
        Vault.Created made = Vault.create(storage, MAC, "Mac");
        String six = "\uD83D\uDE00".repeat(6); // 12 chars, 6 characters
        assertThrows(IllegalArgumentException.class, () -> Vault.addPassword(storage, made.vk(), six.toCharArray()));
        char[] twelve = "\uD83D\uDE00".repeat(12).toCharArray();
        VaultSlot slot = Vault.addPassword(storage, made.vk(), twelve);
        assertArrayEquals(made.vk(), Vault.open(slot, VaultSlot.passwordKey(twelve, slot.params())));
    }

    @Test
    void addingAWayInNeverTouchesAnother() throws Exception {
        Vault.Created made = Vault.create(storage, MAC, "Mac");
        Map<String, byte[]> before = Map.copyOf(cloud.files);

        Vault.Computer pc = Vault.addComputer(storage, made.vk(), PC, "PC de la oficina");
        Vault.addPassword(storage, made.vk(), "una clave muy larga".toCharArray());

        before.forEach((path, bytes) -> assertArrayEquals(bytes, cloud.files.get(path), "untouched: " + path));
        List<VaultSlot> slots = Vault.slots(storage);
        assertEquals(4, slots.size());
        VaultSlot pcSlot = slots.stream().filter(s -> s.id().equals(PC)).findFirst().orElseThrow();
        assertEquals("PC de la oficina", pcSlot.label());
        assertArrayEquals(made.vk(), Vault.open(pcSlot, pc.key()));
        assertArrayEquals(made.vk(), Vault.open(Vault.slots(storage).stream().filter(s -> s.id().equals(MAC))
            .findFirst().orElseThrow(), made.computer().key()), "the first computer still opens it");
    }

    @Test
    void aNewCodeReplacesTheOld() throws Exception {
        Vault.Created made = Vault.create(storage, MAC, "Mac");
        RecoveryCode fresh = Vault.newRecovery(storage, made.vk());

        List<VaultSlot> recovery = Vault.slots(storage).stream().filter(s -> s.method() == VaultSlot.Method.RECOVERY).toList();
        assertEquals(1, recovery.size(), "at most one recovery slot");
        assertThrows(VaultCrypto.Broken.class, () -> Vault.open(recovery.getFirst(), VaultSlot.recoveryKey(made.code())));
        assertArrayEquals(made.vk(), Vault.open(recovery.getFirst(), VaultSlot.recoveryKey(fresh)));
        assertEquals(2, Vault.slots(storage).size(), "the computer's slot stays");
    }

    @Test
    void aWayInIsRemoved() throws Exception {
        Vault.Created made = Vault.create(storage, MAC, "Mac");
        VaultSlot password = Vault.addPassword(storage, made.vk(), "una clave muy larga".toCharArray());
        Vault.addComputer(storage, made.vk(), PC, "PC");

        Vault.removeSlot(storage, PC);
        Vault.removeSlot(storage, password.id());
        assertEquals(Set.of(VaultSlot.Method.RECOVERY, VaultSlot.Method.COMPUTER), byMethod(Vault.slots(storage)).keySet());
        assertThrows(IllegalArgumentException.class, () -> Vault.removeSlot(storage, "../boveda"));
        assertNotNull(Vault.info(storage));
    }

    @Test
    void aShortPasswordIsRefusedWithoutSayingIt() throws Exception {
        Vault.Created made = Vault.create(storage, MAC, "Mac");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> Vault.addPassword(storage, made.vk(), "corta123456".toCharArray()));
        assertFalse(e.getMessage().contains("corta123456"));
        assertEquals(2, Vault.slots(storage).size());
        Vault.addPassword(storage, made.vk(), "exactamente1".toCharArray()); // 12 is enough
    }

    @Test
    void whatIsntASlotInTheAccountIsSkipped() throws Exception {
        Vault.create(storage, MAC, "Mac");
        String stranger = UUID.randomUUID().toString();
        cloud.put(Vault.SLOTS + "/" + stranger + ".json", "{not json".getBytes(StandardCharsets.UTF_8));
        cloud.put(Vault.SLOTS + "/notes.txt", "hola".getBytes(StandardCharsets.UTF_8));
        // A slot moved under another name: its id is its sealing path, so the name must say the same.
        byte[] moved = cloud.files.get(Vault.SLOTS + "/" + MAC + ".json");
        cloud.put(Vault.SLOTS + "/" + UUID.randomUUID() + ".json", moved);

        assertEquals(Set.of(VaultSlot.Method.RECOVERY, VaultSlot.Method.COMPUTER), byMethod(Vault.slots(storage)).keySet());
        assertEquals(2, Vault.slots(storage).size());
    }

    @Test
    void aVaultThatIsntOneReadsAsNone() throws Exception {
        assertThrows(IOException.class, () -> Vault.generation(storage), "no vault, no generation");
        cloud.put(Vault.INFO, "[]".getBytes(StandardCharsets.UTF_8));
        assertNull(Vault.info(storage));
        cloud.put(Vault.INFO, "{\"id\":\"x\",\"created\":\"yesterday\",\"generation\":0}".getBytes(StandardCharsets.UTF_8));
        assertNull(Vault.info(storage));
        cloud.dirs.addAll(List.of(CloudAccount.ROOT, Vault.ROOT, Vault.SLOTS));
        cloud.put(Vault.SLOTS + "/" + UUID.randomUUID() + ".json", "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(Vault.Exists.class, () -> Vault.create(storage, MAC, "Mac"), "something is in the slots: never overwritten");
    }

    @Test
    void aSlotOfAnotherGenerationStillOpens() throws Exception {
        Vault.Created made = Vault.create(storage, MAC, "Mac");
        byte[] key = VaultCrypto.randomKey();
        VaultSlot old = VaultSlot.wrap(made.info().id(), VaultSlot.Method.COMPUTER, "old", 7, key, VaultSlot.newPasswordParams(), made.vk());
        assertArrayEquals(made.vk(), Vault.open(old, key));
    }

    private Set<String> slotFiles() {
        return cloud.files.keySet().stream().filter(p -> p.startsWith(Vault.SLOTS + "/")).collect(Collectors.toSet());
    }

    private List<VaultSlot> recoveries() throws IOException {
        return Vault.slots(storage).stream().filter(s -> s.method() == VaultSlot.Method.RECOVERY).toList();
    }

    private static boolean opens(VaultSlot slot, RecoveryCode code, byte[] vk) {
        try {
            return java.util.Arrays.equals(vk, slot.unwrap(VaultSlot.recoveryKey(code)));
        } catch (VaultCrypto.Broken e) {
            return false;
        }
    }

    private static Map<VaultSlot.Method, VaultSlot> byMethod(List<VaultSlot> slots) {
        return slots.stream().collect(Collectors.toMap(VaultSlot::method, Function.identity()));
    }

    /** What a {@link Racing} account does around a call, by path. */
    private interface Hook {
        void at(String path) throws IOException;
    }

    private static final Hook NOTHING = path -> { };

    /** The account, with something else happening right after each small file goes up, before each delete and each read. */
    private record Racing(Storage s, Hook afterPut, Hook beforeDelete, Hook beforeGet) implements Storage {
        Racing(Storage s, Hook afterPut, Hook beforeDelete) {
            this(s, afterPut, beforeDelete, NOTHING);
        }

        @Override public void check() throws IOException { s.check(); }
        @Override public boolean exists(String path) throws IOException { return s.exists(path); }
        @Override public String stamp(String path) throws IOException { return s.stamp(path); }
        @Override public void mkdirs(String dir) throws IOException { s.mkdirs(dir); }
        @Override public List<String> list(String dir) throws IOException { return s.list(dir); }
        @Override public void delete(String path) throws IOException { beforeDelete.at(path); s.delete(path); }
        @Override public void put(String path, Path file) throws IOException { s.put(path, file); }
        @Override public byte[] get(String path) throws IOException { beforeGet.at(path); return s.get(path); }
        @Override public boolean get(String path, Path target) throws IOException { return s.get(path, target); }

        @Override
        public void put(String path, byte[] data) throws IOException {
            s.put(path, data);
            afterPut.at(path);
        }
    }
}

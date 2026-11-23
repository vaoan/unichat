package app.unichat.cloud;

import app.unichat.settings.Settings;
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
    private Map<String, byte[]> anOldVault() throws Exception {
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
    private Map<String, byte[]> opened(String root, byte[] vk, boolean old) throws Exception {
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
    private void toOldNames(byte[] vk) throws Exception {
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
        assertEquals(2, new com.fasterxml.jackson.databind.ObjectMapper().readTree(cloud.files.get(Vault.INFO)).path("names").asInt());
        assertEquals(made.info().id(), Vault.info(dav()).id());
        assertEquals(java.util.Set.of(DESK, LAPTOP), Vault.computers(dav()).keySet(), "every way in moved");
        assertEquals(2, Vault.slots(dav()).size() - 1, "and the recovery code's");
        for (String p : before.keySet()) {
            if (!p.startsWith(CloudNames.OLD_VAULT)) assertArrayEquals(before.get(p), cloud.files.get(p), "outside the vault, nothing changed: " + p);
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
}

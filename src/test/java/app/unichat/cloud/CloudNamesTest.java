package app.unichat.cloud;

import app.unichat.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The account's old folder names moving to the English ones in place, every round: on the server (nothing goes up
 * again), once, and again for what a computer still on an older Unichat writes there.
 */
class CloudNamesTest {
    private static final String DESK = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44", OLD = "3d8c5b5a-2f3e-4e2a-9c73-8b4f9e3a4d66";
    private static final String TEXT = "3f2a9c1b2d4e 2026-11.jsonl";
    private static final String OLD_SETTINGS = "Unichat/Ajustes/unichat-settings.json", SETTINGS = CloudAccount.SETTINGS_FILE;

    @TempDir
    Path dir;
    private FakeDav cloud;
    private Map<String, String> portableWere, keysWere;

    @BeforeEach
    void start() throws Exception {
        cloud = new FakeDav("u", "p");
        portableWere = Settings.get().portable();
        keysWere = VaultComputer.kept();
        for (String k : VaultComputer.KEYS) Settings.get().set(k, null); // as on a computer new to the account
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

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] settings(String machine, Instant at, String theme) throws java.io.IOException {
        return Bundle.of(machine, at, Map.of("ui.theme", theme), null).toBytes();
    }

    private Set<String> paths() {
        return new TreeSet<>(cloud.files.keySet());
    }

    private long moves() {
        return cloud.log.stream().filter(l -> l.startsWith("MOVE ")).count();
    }

    private static boolean old(String path) {
        return path.startsWith(CloudNames.OLD_MEDIA) || path.startsWith(CloudNames.OLD_SETTINGS) || path.startsWith(CloudNames.OLD_TEXTS)
            || path.startsWith(CloudNames.OLD_VAULT);
    }

    @Test
    void aFreshAccountGetsOnlyTheNewNames() throws Exception {
        VaultComputer desk = new VaultComputer(cloud, dir, DESK).set("ui.theme", "DARK").write("writing-style.md", "corto")
            .write("texts/here/" + TEXT, "{}\n");
        assertNull(desk.round().error());
        assertEquals(new TreeSet<>(Set.of("Unichat/Settings/unichat-settings.json", "Unichat/Settings/unichat-ai.json",
            "Unichat/Texts/" + DESK + "/" + TEXT, "Unichat/Texts/" + DESK + "/index.json", "Unichat/Media/Ana/a.jpg")), paths());
        assertFalse(cloud.dirs.stream().anyMatch(CloudNamesTest::old), "no old folder made: " + cloud.dirs);
        assertEquals(0, moves(), "nothing to move");
    }

    @Test
    void theOldFoldersAreMovedOnce() throws Exception {
        byte[] photo = bytes("foto"), text = bytes("{\"t\":1}\n"), mine = settings(DESK, Instant.now().minusSeconds(60), "DARK");
        cloud.put("Unichat/Multimedia/Ana/a.jpg", photo);
        cloud.put("Unichat/Multimedia/Mamá d/v [1].mp4", bytes("video"));
        cloud.put(OLD_SETTINGS, mine);
        cloud.put("Unichat/Textos/" + DESK + "/" + TEXT, text);
        cloud.put("Unichat/Textos/" + DESK + "/index.json", TextIndex.write(Map.of(TEXT, (long) text.length)));
        VaultComputer desk = new VaultComputer(cloud, dir, DESK).write("texts/here/" + TEXT, "{\"t\":1}\n");

        assertNull(desk.round().error());
        assertFalse(paths().stream().anyMatch(CloudNamesTest::old), "nothing left under an old name: " + paths());
        assertFalse(cloud.dirs.stream().anyMatch(CloudNamesTest::old), "nor any old folder: " + cloud.dirs);
        assertArrayEquals(photo, cloud.files.get("Unichat/Media/Ana/a.jpg"));
        assertArrayEquals(bytes("video"), cloud.files.get("Unichat/Media/Mamá d/v [1].mp4"));
        assertArrayEquals(text, cloud.files.get("Unichat/Texts/" + DESK + "/" + TEXT));
        assertEquals("DARK", desk.get("ui.theme"), "the settings under their new name are this computer's");
        assertTrue(cloud.log.contains("MOVE Unichat/Multimedia Unichat/Media"), "media moved on the server, whole: " + cloud.log);
        assertTrue(cloud.log.contains("MOVE Unichat/Textos Unichat/Texts"), cloud.log.toString());
        assertTrue(cloud.log.contains("MOVE " + OLD_SETTINGS + " " + SETTINGS), cloud.log.toString());
        assertFalse(cloud.log.stream().anyMatch(l -> l.startsWith("PUT Unichat/Media/") || l.equals("PUT " + SETTINGS)),
            "neither media nor the settings went up again: " + cloud.log);

        long moved = moves();
        assertNull(desk.round().error());
        assertEquals(moved, moves(), "once: the next round has nothing to move");
    }

    @Test
    void anOldComputerWritingTheOldFolderIsMovedInEachRound() throws Exception {
        VaultComputer desk = new VaultComputer(cloud, dir, DESK).set("ui.theme", "DARK");
        desk.round();
        // An older Unichat on another computer writes the old names: its settings changed after this one's.
        cloud.put(OLD_SETTINGS, settings(OLD, Instant.now().plusSeconds(60), "LIGHT"));
        cloud.put("Unichat/Textos/" + OLD + "/" + TEXT, bytes("{\"de\":\"otro\"}\n"));
        cloud.put("Unichat/Textos/" + OLD + "/index.json", TextIndex.write(Map.of(TEXT, 14L)));
        cloud.put("Unichat/Multimedia/Ana/b.jpg", bytes("otra"));

        assertNull(desk.round().error());
        assertEquals("LIGHT", desk.get("ui.theme"), "the newer settings won");
        assertEquals("{\"de\":\"otro\"}\n", desk.read("texts/" + OLD + "/" + TEXT), "its texts reached this computer");
        assertArrayEquals(bytes("otra"), cloud.files.get("Unichat/Media/Ana/b.jpg"));
        assertFalse(cloud.files.containsKey("Unichat/Multimedia/Ana/b.jpg"));
        assertFalse(cloud.files.containsKey("Unichat/Textos/" + OLD + "/" + TEXT));
        assertTrue(cloud.files.containsKey(OLD_SETTINGS), "its settings stay where it reads them back, so it never sends them again");

        desk.set("ui.theme", "SYSTEM");
        desk.round();
        int puts = cloud.puts.get();
        long moved = moves();
        assertNull(desk.round().error());
        assertEquals("SYSTEM", desk.get("ui.theme"), "its older copy, still there, never wins over what changed since");
        assertEquals(puts, cloud.puts.get(), "and nothing loops: no round sends anything again");
        assertEquals(moved, moves());

        cloud.put(OLD_SETTINGS, settings(OLD, Instant.now().plusSeconds(120), "DARK"));
        desk.round();
        assertEquals("DARK", desk.get("ui.theme"), "what it changes later comes in again");
    }

    @Test
    void aComputerStillOnTheOldNamesIsNamedInTheUi() throws Exception {
        VaultComputer desk = new VaultComputer(cloud, dir, DESK);
        assertEquals(Set.of(), desk.round().moving().older(), "none before an older Unichat writes anything");
        cloud.put("Unichat/Textos/" + OLD + "/" + TEXT, bytes("{}\n"));
        cloud.put("Unichat/Textos/" + OLD + "/index.json", TextIndex.write(Map.of(TEXT, 3L)));
        Cloud.Status s = desk.round();
        assertEquals(Set.of(OLD), s.moving().older(), "no vault: counted");
        assertTrue(app.unichat.ui.settings.CloudTexts.vaultLines(s, Instant.now()).stream().anyMatch(l -> l.contains("Unichat anterior")),
            app.unichat.ui.settings.CloudTexts.vaultLines(s, Instant.now()).toString());

        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        desk.madeHere(made);
        desk.round(); // the move into the vault, done
        desk.round();
        cloud.put("Unichat/Textos/" + OLD + "/" + TEXT, bytes("{}\n{}\n"));
        cloud.put("Unichat/Textos/" + OLD + "/index.json", TextIndex.write(Map.of(TEXT, 6L)));
        s = desk.round();
        assertEquals(Cloud.VaultState.OPEN, s.vault());
        assertEquals(Set.of(OLD), s.moving().strays().keySet(), "with the vault: named as the computers still sending plain are");
        assertEquals(Set.of(), s.moving().older(), "and only once");
        assertTrue(app.unichat.ui.settings.CloudTexts.vaultLines(s, Instant.now()).contains("Un equipo sin nombre usa una versión sin bóveda: actualízalo."),
            app.unichat.ui.settings.CloudTexts.vaultLines(s, Instant.now()).toString());
        java.util.Arrays.fill(made.vk(), (byte) 0);
    }

    @Test
    void mediaInBothFoldersKeepsOneCopy() throws Exception {
        Storage dav = dav();
        cloud.put("Unichat/Media/Ana/a.jpg", bytes("foto"));
        cloud.put("Unichat/Media/Ana/c.jpg", bytes("la nueva"));
        cloud.put("Unichat/Multimedia/Ana/a.jpg", bytes("foto"));
        cloud.put("Unichat/Multimedia/Ana/c.jpg", bytes("otra cosa"));
        cloud.put("Unichat/Multimedia/Ana/d.jpg", bytes("solo vieja"));
        cloud.put("Unichat/Multimedia/Beto/e.jpg", bytes("e"));
        int puts = cloud.puts.get();

        CloudNames.media(dav);
        assertFalse(cloud.files.containsKey("Unichat/Multimedia/Ana/a.jpg"), "the same file under both: one copy");
        assertArrayEquals(bytes("foto"), cloud.files.get("Unichat/Media/Ana/a.jpg"));
        assertArrayEquals(bytes("la nueva"), cloud.files.get("Unichat/Media/Ana/c.jpg"), "two that differ: both kept");
        assertArrayEquals(bytes("otra cosa"), cloud.files.get("Unichat/Multimedia/Ana/c.jpg"));
        assertArrayEquals(bytes("solo vieja"), cloud.files.get("Unichat/Media/Ana/d.jpg"), "one only under the old name moves");
        assertArrayEquals(bytes("e"), cloud.files.get("Unichat/Media/Beto/e.jpg"), "a folder only under the old name moves whole");
        assertTrue(cloud.log.contains("MOVE Unichat/Multimedia/Beto Unichat/Media/Beto"), cloud.log.toString());
        assertEquals(puts, cloud.puts.get(), "nothing went up again");

        CloudNames.media(dav);
        assertArrayEquals(bytes("otra cosa"), cloud.files.get("Unichat/Multimedia/Ana/c.jpg"), "and stays so: nothing lost");
        assertEquals(puts, cloud.puts.get());
    }

    @Test
    void settingsInBothKeepTheNewest() throws Exception {
        Storage dav = dav();
        Instant now = Instant.now();
        byte[] older = settings(DESK, now.minusSeconds(60), "LIGHT"), newer = settings(DESK, now, "DARK");
        cloud.put(SETTINGS, older);
        cloud.put(OLD_SETTINGS, newer); // this computer's, sent by it before it was updated
        CloudNames.migrate(dav, DESK, new Object(), now);
        assertArrayEquals(newer, cloud.files.get(SETTINGS), "the old name's newer copy won");
        assertFalse(cloud.files.containsKey(OLD_SETTINGS), "and it's gone, held under the new name");

        cloud.put(OLD_SETTINGS, older);
        CloudNames.migrate(dav, DESK, new Object(), now);
        assertArrayEquals(newer, cloud.files.get(SETTINGS), "an older copy under the old name never wins");
        assertFalse(cloud.files.containsKey(OLD_SETTINGS));

        byte[] theirs = settings(OLD, now.plusSeconds(60), "SYSTEM");
        cloud.put(OLD_SETTINGS, theirs); // another computer's, newer
        CloudNames.migrate(dav, DESK, new Object(), now);
        assertArrayEquals(theirs, cloud.files.get(SETTINGS), "another computer's newer copy wins too");
        assertTrue(cloud.files.containsKey(OLD_SETTINGS), "and stays where that computer reads it back");
    }

    @Test
    void theTurnOffMarkMovesToo() throws Exception {
        cloud.put(CloudNames.OLD_OFF, bytes("{\"vault\":\"x\"}"));
        CloudNames.migrate(dav(), DESK, new Object(), Instant.now());
        assertArrayEquals(bytes("{\"vault\":\"x\"}"), cloud.files.get(VaultOff.FILE));
        assertFalse(cloud.files.containsKey(CloudNames.OLD_OFF));
    }

    @Test
    void namesFromTheAccountThatArentOursAreLeft() throws Exception {
        cloud.put("Unichat/Ajustes/notas.txt", bytes("mías"));
        cloud.put("Unichat/Ajustes/unichat-settings.json", bytes("no es un ajuste"));
        cloud.put("Unichat/Texts/" + DESK + "/index.json", bytes("{}"));
        cloud.put("Unichat/Textos/../fuera/" + TEXT, bytes("x"));
        cloud.put("Unichat/Textos/no-es-una-máquina/" + TEXT, bytes("x"));
        cloud.put("Unichat/Textos/" + OLD + "/otra.txt", bytes("x"));
        CloudNames.migrate(dav(), DESK, new Object(), Instant.now());
        assertTrue(cloud.files.containsKey("Unichat/Ajustes/notas.txt"));
        assertTrue(cloud.files.containsKey("Unichat/Ajustes/unichat-settings.json"), "not a part of ours: left");
        assertTrue(cloud.files.containsKey("Unichat/Textos/no-es-una-máquina/" + TEXT));
        assertTrue(cloud.files.containsKey("Unichat/Texts/" + OLD + "/otra.txt"), "a machine's folder moves whole, nothing in it lost");
        assertFalse(cloud.files.keySet().stream().anyMatch(p -> p.contains("fuera") && !p.startsWith("Unichat/Textos/")));
    }

    @Test
    void theNamesAreSwappedBothWays() {
        assertEquals("Unichat/Settings/unichat-ai.json", CloudNames.renamed("Unichat/Ajustes/unichat-ai.json"));
        assertEquals("Unichat/Log/senders.json", CloudNames.renamed("Unichat/Registro/remitentes.json"));
        assertEquals("Unichat/Texts/m/x", CloudNames.renamed("Unichat/Textos/m/x"));
        assertEquals("Unichat/Textosviejos/x", CloudNames.renamed("Unichat/Textosviejos/x"), "a whole name only");
        assertEquals("Unichat/Ajustes/unichat-ai.json", CloudNames.old("Unichat/Settings/unichat-ai.json"));
        assertEquals("Unichat/Bóveda/llaves/a.json", CloudNames.oldStored("Unichat/Vault/keys/a.json"));
        assertEquals("Unichat/Bóveda/boveda.json", CloudNames.oldStored(Vault.INFO));
        assertEquals("Unichat/Bóveda/Textos/m/x", CloudNames.oldStored("Unichat/Vault/Texts/m/x"));
        assertEquals("Unichat/Bóveda/Registro/remitentes.json", CloudNames.oldStored("Unichat/Vault/Log/senders.json"));
        assertEquals("Unichat/Bóveda", CloudNames.oldStored(Vault.ROOT));
        assertEquals(Vault.SLOTS + "/a.json", CloudNames.newStored("Unichat/Bóveda/llaves/a.json"));
        assertEquals("Unichat/Vault/Settings/unichat-ai.json", CloudNames.newStored("Unichat/Bóveda/Ajustes/unichat-ai.json"));
        assertEquals("Unichat/Settings/x", CloudNames.oldStored("Unichat/Settings/x"), "outside the vault: as it is");
    }
}

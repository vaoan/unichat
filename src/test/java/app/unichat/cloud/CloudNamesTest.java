package app.unichat.cloud;

import app.unichat.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertEquals("DARK", desk.get("ui.theme"), "the settings under their new name");
        assertTrue(cloud.log.contains("MOVE Unichat/Multimedia Unichat/Media"), "media moved on the server, whole: " + cloud.log);
        assertTrue(cloud.log.contains("MOVE Unichat/Textos Unichat/Texts"), cloud.log.toString());
        assertTrue(cloud.log.contains("MOVE " + OLD_SETTINGS + " " + SETTINGS), cloud.log.toString());
        assertFalse(cloud.log.stream().anyMatch(l -> l.startsWith("PUT Unichat/Media/") || l.equals("PUT " + SETTINGS)),
            "neither media nor the settings went up again: " + cloud.log);

        int asked = cloud.asked.size();
        assertNull(desk.round().error());
        assertEquals(List.of(), cloud.asked.subList(asked, cloud.asked.size()).stream().filter(CloudNamesTest::old).toList(),
            "once: done, the old names are never looked for again");
    }

    @Test
    void theOldFoldersAreGoneOnceTheMoveIsConfirmed() throws Exception {
        Storage dav = dav();
        cloud.put("Unichat/Media/Ana/a.jpg", bytes("foto")); // a move cut short: both folders there
        cloud.put("Unichat/Multimedia/Ana/a.jpg", bytes("foto"));
        cloud.put("Unichat/Multimedia/Ana/c.jpg", bytes("otra cosa"));
        cloud.put("Unichat/Media/Ana/c.jpg", bytes("la nueva"));
        cloud.put("Unichat/Textos/" + DESK + "/" + TEXT, bytes("{}\n"));
        cloud.put("Unichat/Texts/" + DESK + "/" + TEXT, bytes("{}\n{}\n"));
        assertFalse(CloudNames.migrate(dav, new Object()), "something under media's old name doesn't match: its check waits");
        assertFalse(cloud.dirs.contains(CloudNames.OLD_TEXTS), "the texts' old folder confirmed and gone");
        assertArrayEquals(bytes("otra cosa"), cloud.files.get("Unichat/Multimedia/Ana/c.jpg"), "a file that differs keeps its folder");
        assertArrayEquals(bytes("la nueva"), cloud.files.get("Unichat/Media/Ana/c.jpg"));
        assertFalse(Settings.get().getBool(CloudNames.DONE, false), "not done while anything is left");

        cloud.put("Unichat/Media/Ana/c.jpg", bytes("otra cosa")); // the same now
        assertTrue(CloudNames.migrate(dav, new Object()));
        assertFalse(paths().stream().anyMatch(CloudNamesTest::old), paths().toString());
        assertFalse(cloud.dirs.stream().anyMatch(CloudNamesTest::old), "every old folder deleted whole: " + cloud.dirs);
        assertTrue(Settings.get().getBool(CloudNames.DONE, false));
    }

    @Test
    void aMoveInterruptedBeforeTheCleanupFinishesItNextRound() throws Exception {
        cloud.put(OLD_SETTINGS, settings(DESK, Instant.now(), "DARK"));
        cloud.put(SETTINGS, settings(DESK, Instant.now().minusSeconds(60), "LIGHT"));
        cloud.put("Unichat/Multimedia/Ana/a.jpg", bytes("foto"));
        cloud.put("Unichat/Media/Ana/b.jpg", bytes("b"));
        cloud.failDeleteOnce.add(CloudNames.OLD_SETTINGS);
        VaultComputer desk = new VaultComputer(cloud, dir, DESK);
        assertTrue(desk.round().error() != null, "told");
        assertTrue(cloud.files.containsKey(OLD_SETTINGS), "cut short before the old folder went");
        assertEquals("DARK", Bundle.parse(cloud.files.get(SETTINGS)).settings().get("ui.theme"), "the newer copy already under the new name");
        assertNull(desk.get("ui.theme"), "and the settings waited: one was still under its old name");
        assertNull(desk.round().error());
        assertEquals("DARK", desk.get("ui.theme"));
        assertFalse(paths().stream().anyMatch(CloudNamesTest::old), "finished: " + paths());
        assertArrayEquals(bytes("foto"), cloud.files.get("Unichat/Media/Ana/a.jpg"));
        assertTrue(desk.get(CloudNames.DONE) != null);
    }

    @Test
    void aPlainMoveThatFailsSendsNoSettingsThatRound() throws Exception {
        byte[] theirs = settings(OLD, Instant.now().minusSeconds(60), "LIGHT"); // another computer's, under the old name only
        cloud.put(OLD_SETTINGS, theirs);
        cloud.failListOnce.add(CloudNames.OLD_SETTINGS);
        VaultComputer desk = new VaultComputer(cloud, dir, DESK).set("ui.theme", "DARK"); // new to the account
        assertNotNull(desk.round().error(), "told");
        assertFalse(cloud.files.containsKey(SETTINGS), "its own settings didn't go up over the ones still under the old name");
        assertNull(desk.round().error());
        assertEquals("LIGHT", desk.get("ui.theme"), "the account's settings came in once moved, as for any computer new to it");
    }

    @Test
    void mediaInBothFoldersKeepsOneCopy() throws Exception {
        cloud.put("Unichat/Media/Ana/a.jpg", bytes("foto"));
        cloud.put("Unichat/Multimedia/Ana/a.jpg", bytes("foto"));
        cloud.put("Unichat/Multimedia/Ana/d.jpg", bytes("solo vieja"));
        cloud.put("Unichat/Multimedia/Beto/e.jpg", bytes("e"));
        int puts = cloud.puts.get();
        assertTrue(CloudNames.migrate(dav(), new Object()));
        assertArrayEquals(bytes("foto"), cloud.files.get("Unichat/Media/Ana/a.jpg"), "the same file under both: one copy");
        assertArrayEquals(bytes("solo vieja"), cloud.files.get("Unichat/Media/Ana/d.jpg"), "one only under the old name moves");
        assertArrayEquals(bytes("e"), cloud.files.get("Unichat/Media/Beto/e.jpg"), "a folder only under the old name moves whole");
        assertTrue(cloud.log.contains("MOVE Unichat/Multimedia/Beto Unichat/Media/Beto"), cloud.log.toString());
        assertFalse(paths().stream().anyMatch(CloudNamesTest::old), paths().toString());
        assertEquals(puts, cloud.puts.get(), "nothing went up again");
    }

    @Test
    void aMediaFolderThatWontMoveHoldsBackOnlyTheCheck() throws Exception {
        cloud.put("Unichat/Multimedia/Beto/e.jpg", bytes("e"));
        cloud.failMoveOnce.add(CloudNames.OLD_MEDIA);
        VaultComputer desk = new VaultComputer(cloud, dir, DESK);
        desk.round();
        assertTrue(cloud.files.containsKey("Unichat/Media/Ana/a.jpg"), "media still goes up");
        assertNull(desk.get(MediaTurn.LAST_VERIFY), "the check waits: it would send again what's under the old name");
        assertTrue(cloud.files.containsKey("Unichat/Multimedia/Beto/e.jpg"));
        assertNull(desk.round().error());
        assertArrayEquals(bytes("e"), cloud.files.get("Unichat/Media/Beto/e.jpg"), "the next round moves it");
        assertTrue(desk.get(MediaTurn.LAST_VERIFY) != null, "and checks");
    }

    @Test
    void settingsInBothKeepTheNewest() throws Exception {
        Instant now = Instant.now();
        byte[] older = settings(DESK, now.minusSeconds(60), "LIGHT"), newer = settings(DESK, now, "DARK");
        cloud.put(SETTINGS, older);
        cloud.put(OLD_SETTINGS, newer);
        CloudNames.migrate(dav(), new Object());
        assertArrayEquals(newer, cloud.files.get(SETTINGS), "the old name's newer copy won");
        assertFalse(cloud.files.containsKey(OLD_SETTINGS), "and its folder is gone, confirmed");

        Settings.get().set(CloudNames.DONE, null);
        cloud.put(OLD_SETTINGS, settings(DESK, now.minusSeconds(120), "SYSTEM"));
        CloudNames.migrate(dav(), new Object());
        assertArrayEquals(newer, cloud.files.get(SETTINGS), "an older copy under the old name never wins");
        assertFalse(cloud.files.containsKey(OLD_SETTINGS));
    }

    @Test
    void theTurnOffMarkMovesToo() throws Exception {
        cloud.put(CloudNames.OLD_OFF, bytes("{\"vault\":\"x\"}"));
        CloudNames.migrate(dav(), new Object());
        assertArrayEquals(bytes("{\"vault\":\"x\"}"), cloud.files.get(VaultOff.FILE));
        assertFalse(cloud.files.containsKey(CloudNames.OLD_OFF));
    }

    @Test
    void namesFromTheAccountThatArentOursAreLeft() throws Exception {
        cloud.put("Unichat/Ajustes/notas.txt", bytes("mías"));
        cloud.put("Unichat/Texts/" + DESK + "/index.json", bytes("{}"));
        cloud.put("Unichat/Textos/no-es-una-máquina/" + TEXT, bytes("x"));
        cloud.put("Unichat/Textos/" + OLD + "/otra.txt", bytes("x"));
        assertTrue(CloudNames.migrate(dav(), new Object()), "media's old name has nothing");
        assertTrue(cloud.files.containsKey("Unichat/Ajustes/notas.txt"), "not ours: kept, and its folder with it");
        assertTrue(cloud.files.containsKey("Unichat/Textos/no-es-una-máquina/" + TEXT));
        assertTrue(cloud.files.containsKey("Unichat/Texts/" + OLD + "/otra.txt"), "a machine's folder moves whole, nothing in it lost");
        assertFalse(Settings.get().getBool(CloudNames.DONE, false), "never done while they're there");
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

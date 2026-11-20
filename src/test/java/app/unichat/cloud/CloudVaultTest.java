package app.unichat.cloud;

import app.unichat.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cloud's round choosing plain, sealed or nothing: today's round without a vault, everything personal sealed with it open
 * here, and nothing personal while it's locked, declined or removed here. Media goes by its own switch in all of them.
 */
class CloudVaultTest {
    private static final String DESK = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44", LAPTOP = "2c7b4a4f-1e2d-4d1f-8b62-7a3e8d2f3c55",
        OTHER = "3d8c5b5a-2f3e-4e2a-9c73-8b4f9e3a4d66";
    private static final String TEXT = "3f2a9c1b2d4e 2026-11.jsonl", TRANSCRIPT = "a".repeat(64) + ".txt";
    private static final String SETTINGS = CloudAccount.SETTINGS_FILE, AI = "Unichat/Ajustes/unichat-ai.json",
        PHOTO = CloudAccount.MEDIA + "/Ana/a.jpg";

    @TempDir
    Path dir;
    private FakeDav cloud;
    private Map<String, String> portableWere, keysWere;

    @BeforeEach
    void start() throws Exception {
        cloud = new FakeDav("u", "p");
        portableWere = Settings.get().portable();
        keysWere = VaultComputer.kept();
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

    private VaultComputer computer(String machine) throws Exception {
        return new VaultComputer(cloud, dir, machine);
    }

    private Map<String, byte[]> files() {
        return new HashMap<>(cloud.files);
    }

    /** The paths written or changed since {@code before}. */
    private Set<String> written(Map<String, byte[]> before) {
        Set<String> out = new TreeSet<>();
        cloud.files.forEach((p, d) -> { if (!Arrays.equals(d, before.get(p))) out.add(p); });
        return out;
    }

    private static boolean plain(String path) {
        return !path.startsWith(Vault.ROOT + "/") && !path.startsWith(CloudAccount.MEDIA + "/");
    }

    private VaultComputer withEverything(VaultComputer c) throws Exception {
        return c.set("ui.theme", "DARK").write("writing-style.md", "escribo corto").write("texts/here/" + TEXT, "{}\n")
            .write("pinned-messages.json", "{\"!c\":[]}").write("transcripts/" + TRANSCRIPT, "hola");
    }

    @Test
    void noVaultIsTodaysRound() throws Exception {
        VaultComputer desk = withEverything(computer(DESK)).set("server.telegram.apiId", "12345");
        Cloud.Status s = desk.round();
        assertNull(s.error());
        assertEquals(Cloud.VaultState.NONE, s.vault());
        assertEquals(Cloud.VaultState.NONE, desk.cloud.vaultState());
        assertEquals(new TreeSet<>(Set.of(SETTINGS, AI, "Unichat/Textos/" + DESK + "/" + TEXT, "Unichat/Textos/" + DESK + "/index.json", PHOTO)),
            new TreeSet<>(cloud.files.keySet()), "today's paths, and no secret, pin nor transcript plain");
        assertFalse(new String(cloud.files.get(SETTINGS), StandardCharsets.UTF_8).contains("12345"));
    }

    @Test
    void aLockedVaultSendsNothingPlain() throws Exception {
        Vault.create(dav(), OTHER, "Otro");
        VaultComputer desk = withEverything(computer(DESK)).set("server.telegram.apiId", "12345");
        Map<String, byte[]> before = files();
        Cloud.Status s = desk.round();
        assertNull(s.error());
        assertEquals(Cloud.VaultState.LOCKED, s.vault());
        Set<String> written = written(before);
        assertEquals(Set.of(), written.stream().filter(CloudVaultTest::plain).collect(java.util.stream.Collectors.toSet()), "nothing personal, plain");
        assertTrue(written.contains(PHOTO), "media goes up all the same");
        assertEquals(Set.of(PHOTO), written, "and the vault's waits until it's opened here");
    }

    @Test
    void aComputerGoingOnWithoutTheVaultIsAsBeforeAndSendsNothingPlain() throws Exception {
        Vault.create(dav(), OTHER, "Otro");
        cloud.put(SETTINGS, Bundle.of(OTHER, java.time.Instant.now(), Map.of("ui.theme", "LIGHT"), null).toBytes()); // an older computer's
        VaultComputer desk = withEverything(computer(DESK)).set(VaultParts.DECLINED, "true");
        Map<String, byte[]> before = files();
        Cloud.Status s = desk.round();
        assertNull(s.error());
        assertEquals(Cloud.VaultState.DECLINED, s.vault());
        assertEquals("DARK", desk.get("ui.theme"), "its settings are its own");
        desk.set("ui.theme", "SYSTEM").set("server.telegram.apiId", "777"); // changed by hand, as before the vault
        desk.round();
        assertEquals("SYSTEM", desk.get("ui.theme"));
        assertEquals("777", desk.get("server.telegram.apiId"));
        assertEquals(Set.of(PHOTO), written(before), "no plain file written; media still up");
    }

    @Test
    void aComputerWhoseSlotWentIsRemovedAndSendsNothing() throws Exception {
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        VaultComputer desk = withEverything(computer(DESK)).opened(made.info(), made.computer());
        Vault.removeSlot(dav(), DESK);
        Map<String, byte[]> before = files();
        Cloud.Status s = desk.round();
        assertEquals(Cloud.VaultState.REMOVED, s.vault());
        assertEquals(Set.of(PHOTO), written(before), "only media");
        assertTrue(cloud.files.keySet().containsAll(before.keySet()), "and nothing deleted");
        assertEquals(made.info().id(), desk.get(VaultParts.ID), "its key kept: removing it is the person's to say");

        desk.set(VaultParts.KEY, "no es base64 ¡");
        assertEquals(Cloud.VaultState.REMOVED, desk.round().vault(), "a key here that isn't one opens nothing");
    }

    @Test
    void aVaultGoneFromTheAccountIsTodaysRoundAgainAndItsKeyForgotten() throws Exception {
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        VaultComputer desk = computer(DESK).opened(made.info(), made.computer()).set(VaultParts.DECLINED, "true");
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        VaultOff.write(dav(), made.info(), made.vk()); // turned off elsewhere: said so, then deleted
        dav().delete(Vault.ROOT);
        Cloud.Status s = desk.round();
        assertEquals(Cloud.VaultState.NONE, s.vault());
        assertNull(desk.get(VaultParts.ID));
        assertNull(desk.get(VaultParts.KEY));
        assertNull(desk.get(VaultParts.PRIVATE));
        assertNull(desk.get(VaultMove.MOVED_AT), "a vault made later is moved into again");
        assertNull(desk.get(VaultParts.DECLINED), "and asked about afresh");
        assertTrue(cloud.files.containsKey(SETTINGS), "back to the plain settings");

        Vault.Created again = Vault.create(dav(), OTHER, "Otro");
        cloud.put(Vault.INFO, "{no es una bóveda".getBytes(StandardCharsets.UTF_8));
        desk.opened(again.info(), again.computer());
        Map<String, byte[]> before = files();
        assertEquals(Cloud.VaultState.LOCKED, desk.round().vault(), "a vault file that doesn't read: closed, never forgotten");
        assertEquals(again.info().id(), desk.get(VaultParts.ID));
        assertEquals(Set.of(), written(before).stream().filter(CloudVaultTest::plain).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void anOpenVaultCarriesSecretsSettingsAiTextsPinnedAndTranscripts() throws Exception {
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        Vault.Computer laptopWay = Vault.addComputer(dav(), made.vk(), LAPTOP, "Portátil");
        VaultComputer desk = withEverything(computer(DESK)).opened(made.info(), made.computer())
            .set("server.telegram.apiId", "12345").set("server.telegram.apiHash", "0123456789abcdef0123456789abcdef")
            .set("gif.giphy.key", "giphy").set("cloud.dropboxAppKey", "dbx").set("stt.command", "say hola");
        VaultComputer laptop = computer(LAPTOP).opened(made.info(), laptopWay).set("ui.theme", "LIGHT")
            .write("transcripts/" + "b".repeat(64) + ".txt", "adiós");

        Map<String, byte[]> before = files();
        assertNull(desk.round().error());
        Cloud.Status s = laptop.round();
        assertNull(s.error());
        assertEquals(Cloud.VaultState.OPEN, s.vault());
        assertEquals("12345", laptop.get("server.telegram.apiId"), "the Telegram keys, applied: read where they're used");
        assertEquals("0123456789abcdef0123456789abcdef", laptop.get("server.telegram.apiHash"));
        assertEquals("giphy", laptop.get("gif.giphy.key"));
        assertEquals("dbx", laptop.get("cloud.dropboxAppKey"));
        assertNull(laptop.get("stt.command"), "never what runs here");
        assertEquals("DARK", laptop.get("ui.theme"), "the settings");
        assertEquals("escribo corto", laptop.read("writing-style.md"), "what the AI learned");
        assertEquals("{}\n", laptop.read("texts/" + DESK + "/" + TEXT), "what chats said");
        assertEquals("{\"!c\":[]}", laptop.read("pinned-messages.json"), "the pins");
        assertEquals(1, laptop.pinnedAdopted.get(), "and they're read again");
        assertEquals("hola", laptop.read("transcripts/" + TRANSCRIPT), "the transcripts");

        desk.round();
        assertEquals("adiós", desk.read("transcripts/" + "b".repeat(64) + ".txt"), "each computer's transcripts reach the other");
        assertEquals("hola", desk.read("transcripts/" + TRANSCRIPT));
        assertEquals(Set.of(), written(before).stream().filter(CloudVaultTest::plain).collect(java.util.stream.Collectors.toSet()),
            "nothing of it plain");
        for (String p : written(before)) {
            if (p.startsWith(Vault.ROOT + "/")) assertFalse(new String(cloud.files.get(p), StandardCharsets.ISO_8859_1).contains("12345"), p);
        }
    }

    @Test
    void aTranscriptsNameFromTheVaultNeverBecomesAPathOutsideItsFolder() throws Exception {
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        SealedStorage sealed = new SealedStorage(dav(), () -> made.vk().clone());
        sealed.mkdirs("Unichat/Ajustes");
        sealed.put(VaultParts.TRANSCRIPTS_FILE, ("{\"entries\":{\"../../fuera.txt\":\"x\",\"" + "c".repeat(64) + ".txt\":\"bien\","
            + "\"" + "C".repeat(64) + ".txt\":\"x\",\"..\":\"x\"}}").getBytes(StandardCharsets.UTF_8));
        VaultComputer desk = computer(DESK).opened(made.info(), made.computer());
        assertNull(desk.round().error());
        assertEquals("bien", desk.read("transcripts/" + "c".repeat(64) + ".txt"));
        try (var all = Files.walk(dir)) {
            assertEquals(List.of(), all.filter(p -> p.getFileName().toString().contains("fuera")).toList());
        }
        try (var here = Files.list(desk.data.resolve("transcripts"))) {
            assertEquals(1, here.count(), "only a name of ours became a file");
        }
    }

    @Test
    void theVaultOpeningHereReadsItsFilesAfreshThoughTheirStampsAreThePlainOnes() throws Exception {
        VaultComputer desk = computer(DESK).set("ui.theme", "DARK");
        desk.round(); // plain: the account's settings file at its first version
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        VaultComputer laptop = computer(LAPTOP).opened(made.info(), Vault.addComputer(dav(), made.vk(), LAPTOP, "Portátil")).set("ui.theme", "LIGHT");
        laptop.round(); // the vault's settings file at its first version too: the same stamp, another file
        assertEquals(cloud.versions.get(SETTINGS), cloud.versions.get(Vault.ROOT + "/Ajustes/unichat-settings.json"));
        desk.opened(made.info(), made.computer());
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        assertEquals("LIGHT", desk.get("ui.theme"), "the vault's settings were read, not taken for the plain ones seen");
    }

    @Test
    void textsAskedAloneGoSealedWithTheVaultOpenAndNowhereWhileLocked() throws Exception {
        Vault.Created made = Vault.create(dav(), OTHER, "Otro");
        VaultComputer desk = withEverything(computer(DESK)).set("cloud.media", "false");
        Map<String, byte[]> before = files();
        desk.textsNow();
        assertEquals(Set.of(), written(before), "locked here: nowhere");
        desk.opened(made.info(), Vault.addComputer(dav(), made.vk(), DESK, "Escritorio"));
        before = files();
        desk.textsNow();
        assertEquals(Set.of(Vault.ROOT + "/Textos/" + DESK + "/" + TEXT, Vault.ROOT + "/Textos/" + DESK + "/index.json"), written(before),
            "open: sealed, media's switch off or not");
    }

    @Test
    void aKeyOnlyThisComputerHadStaysAndReachesTheOthers() throws Exception {
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        Vault.Computer laptopWay = Vault.addComputer(dav(), made.vk(), LAPTOP, "Portátil");
        VaultComputer desk = computer(DESK).opened(made.info(), made.computer()).set("server.telegram.apiId", "12345");
        desk.round(); // the vault's secrets: the Telegram id only
        VaultComputer laptop = computer(LAPTOP).opened(made.info(), laptopWay).set("gif.giphy.key", "de-la-portátil");
        laptop.round();
        assertEquals("de-la-portátil", laptop.get("gif.giphy.key"), "opening the vault never loses a key typed here");
        assertEquals("12345", laptop.get("server.telegram.apiId"), "and takes the vault's");
        laptop.round();
        desk.round();
        assertEquals("de-la-portátil", desk.get("gif.giphy.key"), "the next round sends it to the others");
        assertEquals("12345", desk.get("server.telegram.apiId"));
        int puts = cloud.puts.get();
        laptop.round();
        desk.round();
        assertEquals(puts, cloud.puts.get(), "and then it rests");
    }

    @Test
    void aVaultFileThatDoesntReadIsNeverTakenForNoVault() throws Exception {
        cloud.dirs.add("Unichat");
        cloud.dirs.add(Vault.ROOT);
        cloud.put(Vault.INFO, "{no es una bóveda".getBytes(StandardCharsets.UTF_8));
        VaultComputer desk = withEverything(computer(DESK));
        Map<String, byte[]> before = files();
        assertEquals(Cloud.VaultState.LOCKED, desk.round().vault());
        assertEquals(Set.of(PHOTO), written(before), "nothing plain, though no vault was ever opened here");
        assertEquals(Cloud.VaultState.DECLINED, desk.set(VaultParts.DECLINED, "true").round().vault());
    }

    @Test
    void textsAskedAloneAfterTheVaultOpenedReadItsIndexesAfresh() throws Exception {
        String later = "3f2a9c1b2d4e 2026-12.jsonl";
        VaultComputer laptop = computer(LAPTOP).write("texts/here/" + TEXT, "{}\n");
        laptop.round(); // plain: its index at its first version
        VaultComputer desk = computer(DESK);
        desk.round();
        assertEquals("{}\n", desk.read("texts/" + LAPTOP + "/" + TEXT));
        Vault.Created made = Vault.create(dav(), LAPTOP, "Portátil");
        laptop.opened(made.info(), made.computer()).write("texts/here/" + later, "{\"x\":1}\n");
        laptop.round(); // the vault's index of the laptop at its first version too
        assertEquals(cloud.versions.get("Unichat/Textos/" + LAPTOP + "/index.json"), cloud.versions.get(Vault.ROOT + "/Textos/" + LAPTOP + "/index.json"));
        desk.opened(made.info(), Vault.addComputer(dav(), made.vk(), DESK, "Escritorio"));
        desk.textsNow(); // before any round here with the vault open
        assertEquals("{\"x\":1}\n", desk.read("texts/" + LAPTOP + "/" + later), "the vault's index read, not taken for the plain one seen");
    }

    @Test
    void mediaAndTheVaultAreIndependent() throws Exception {
        for (boolean media : new boolean[] {true, false}) {
            for (boolean vault : new boolean[] {true, false}) {
                String what = "media " + media + ", vault " + vault;
                cloud.files.clear();
                cloud.dirs.clear();
                VaultComputer c = withEverything(computer(java.util.UUID.randomUUID().toString())).set("cloud.media", Boolean.toString(media));
                if (vault) {
                    Vault.Created made = Vault.create(dav(), c.get("cloud.machine"), "Este");
                    c.opened(made.info(), made.computer());
                }
                Cloud.Status s = c.round();
                assertNull(s.error(), what);
                assertEquals(media, cloud.files.containsKey(PHOTO), what + ": media");
                assertEquals(vault, cloud.files.containsKey(Vault.ROOT + "/Textos/" + c.get("cloud.machine") + "/" + TEXT), what + ": sealed texts");
                assertEquals(vault, cloud.files.containsKey(Vault.ROOT + "/Ajustes/unichat-secrets.json"), what + ": sealed parts");
                assertEquals(!vault && media, cloud.files.containsKey("Unichat/Textos/" + c.get("cloud.machine") + "/" + TEXT), what + ": plain texts");
                assertEquals(!vault, cloud.files.containsKey(SETTINGS), what + ": plain settings");
                assertEquals(Boolean.toString(media), c.get("cloud.media"), what + ": the vault leaves media's switch alone");
                assertEquals(vault ? Cloud.VaultState.OPEN : Cloud.VaultState.NONE, s.vault(), what + ": and media the vault");
            }
        }
    }

    @Test
    void aVaultMadeHereMovesWhatWasPlainIn() throws Exception {
        VaultComputer desk = withEverything(computer(DESK));
        desk.round(); // today's: plain
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        desk.opened(made.info(), made.computer());
        Cloud.Status s = desk.round();
        assertNull(s.error());
        assertEquals(Set.of(PHOTO), cloud.files.keySet().stream().filter(p -> !p.startsWith(Vault.ROOT + "/"))
            .collect(java.util.stream.Collectors.toSet()), "nothing plain left but media");
        assertEquals(4, s.moving().files(), "the settings, the AI's, the text and its index");
        assertNotNull(s.moving().at());
        assertEquals(Map.of(), s.moving().strays());

        VaultComputer laptop = computer(LAPTOP).opened(made.info(), Vault.addComputer(dav(), made.vk(), LAPTOP, "Portátil"));
        laptop.round();
        assertEquals("{}\n", laptop.read("texts/" + DESK + "/" + TEXT), "the texts sent plain before reach the others, sealed: sent again by the one that wrote them");
        assertEquals(4, desk.round().moving().files(), "and the count stays");
    }

    @Test
    void anOlderComputerStillSendingPlainIsNamedAndNeverTaken() throws Exception {
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        VaultComputer desk = computer(DESK).opened(made.info(), made.computer()).set("ui.theme", "DARK");
        desk.round();
        Vault.addComputer(dav(), made.vk(), OTHER, "Viejo");
        cloud.dirs.add("Unichat/Ajustes"); // the older computer's folder, as it makes it
        cloud.put(SETTINGS, Bundle.of(OTHER, java.time.Instant.now().plusSeconds(60), Map.of("ui.theme", "LIGHT"), null).toBytes());
        Cloud.Status s = desk.round();
        assertNull(s.error());
        assertEquals(Map.of(OTHER, "Viejo"), s.moving().strays(), "named by its computer's name in the vault");
        assertEquals("Viejo", s.moving().strayName(OTHER));
        assertEquals("", s.moving().strayName(LAPTOP));
        assertEquals("DARK", desk.get("ui.theme"), "its plain settings are never taken");
        assertTrue(cloud.files.containsKey(SETTINGS), "nor deleted round after round");
    }

    @Test
    void aFileThatFailsToMoveInIsTheRoundsErrorAndMediaStillGoes() throws Exception {
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        VaultComputer desk = computer(DESK).opened(made.info(), made.computer());
        for (String d : new String[] {"Unichat/Textos", "Unichat/Textos/" + OTHER}) cloud.dirs.add(d);
        cloud.put("Unichat/Textos/" + OTHER + "/" + TEXT, "{}\n".getBytes(StandardCharsets.UTF_8)); // a computer without the vault's
        cloud.put(Vault.ROOT + "/Textos/" + OTHER + "/" + TEXT, "dañado".getBytes(StandardCharsets.UTF_8));
        Cloud.Status s = desk.round();
        assertTrue(s.error() != null && s.error().contains("bóveda"), "told: " + s.error());
        assertTrue(cloud.files.containsKey(PHOTO), "media went all the same");
        assertTrue(cloud.files.containsKey("Unichat/Textos/" + OTHER + "/" + TEXT), "and the plain copy stays");
    }

    @Test
    void anotherVaultInTheAccountForgetsTheMoveIntoTheOldOne() throws Exception {
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        VaultComputer desk = computer(DESK).opened(made.info(), made.computer());
        desk.round();
        assertNotNull(desk.get(VaultMove.MOVED_AT));
        dav().delete(Vault.ROOT);
        Vault.create(dav(), OTHER, "Otro"); // replaced before any round here saw it gone
        assertEquals(Cloud.VaultState.LOCKED, desk.round().vault());
        assertNull(desk.get(VaultMove.MOVED_AT), "the new vault is moved into from the start");
    }

    @Test
    void aLaunchSendsNoTextAgainAndTheVaultOpeningHereSendsThemOnce() throws Exception {
        VaultComputer desk = withEverything(computer(DESK));
        desk.round(); // plain
        VaultComputer launched = computer(DESK);
        launched.values.putAll(desk.values);
        int puts = cloud.puts.get();
        assertNull(launched.round().error());
        assertEquals(puts, cloud.puts.get(), "a launch, plain: nothing goes up again");

        Vault.Created made = Vault.create(dav(), OTHER, "Otro");
        puts = cloud.puts.get();
        assertEquals(Cloud.VaultState.LOCKED, launched.round().vault());
        assertEquals(puts, cloud.puts.get(), "a vault elsewhere, not opened here: nothing");
        String sealedText = Vault.ROOT + "/Textos/" + DESK + "/" + TEXT;
        launched.opened(made.info(), Vault.addComputer(dav(), made.vk(), DESK, "Escritorio"));
        assertNull(launched.round().error());
        assertTrue(cloud.files.containsKey(sealedText), "opened here: its texts go again, sealed");
        int sent = cloud.versions.get(sealedText);

        VaultComputer again = computer(DESK);
        again.values.putAll(launched.values);
        assertNull(again.round().error());
        assertEquals(sent, cloud.versions.get(sealedText), "a launch with the vault open: not again");
    }
}

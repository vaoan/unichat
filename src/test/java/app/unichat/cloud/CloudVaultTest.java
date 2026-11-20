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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    /** What each computer holds of the settings: swapped in and out of the one {@link Settings} around its rounds. */
    private static final List<String> KEYS = List.of("cloud.machine", VaultParts.ID, VaultParts.KEY, VaultParts.PRIVATE, VaultParts.DECLINED,
        "cloud.base.settings", "cloud.base.ai", "cloud.base.secrets", "cloud.base.pinned", "cloud.media", "cloud.settings", "cloud.lastVerify",
        "server.telegram.apiId", "server.telegram.apiHash", "gif.giphy.key", "cloud.dropboxAppKey", "cloud.pcloudClientId", "stt.command",
        "ui.theme");

    @TempDir
    Path dir;
    private FakeDav cloud;
    private Map<String, String> portableWere, keysWere;

    @BeforeEach
    void start() throws Exception {
        cloud = new FakeDav("u", "p");
        portableWere = Settings.get().portable();
        keysWere = new HashMap<>();
        for (String k : KEYS) keysWere.put(k, Settings.get().getString(k, null));
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

    /** One computer of the person's: its own folder, its own settings, its own {@link Cloud}. */
    private final class Computer {
        final Map<String, String> values = new HashMap<>();
        final Path data;
        final Cloud cloud;
        final AtomicInteger pinnedAdopted = new AtomicInteger();

        Computer(String machine) throws Exception {
            values.put("cloud.machine", machine);
            data = Files.createDirectories(dir.resolve(machine));
            Path photos = Files.createDirectories(data.resolve("Ana"));
            Files.writeString(photos.resolve("a.jpg"), "foto");
            CloudAccount account = new CloudAccount(CloudAccount.Service.OTHER, CloudVaultTest.this.cloud.url(), "u", "p");
            in(); // its machine id, which the texts are made with
            cloud = Cloud.create(() -> account, data, () -> null, b -> true, () -> { }, () -> List.of(photos),
                new Cloud.Texts(data.resolve("texts"), p -> false, s -> { }),
                new Cloud.Personal(data.resolve("pinned-messages.json"), pinnedAdopted::incrementAndGet, data.resolve("transcripts")));
            out();
        }

        private void in() {
            for (String k : KEYS) Settings.get().set(k, values.get(k));
        }

        private void out() {
            for (String k : KEYS) values.put(k, Settings.get().getString(k, null));
        }

        Computer set(String key, String value) {
            values.put(key, value);
            return this;
        }

        String get(String key) {
            return values.get(key);
        }

        Computer write(String file, String text) throws Exception {
            Path p = data.resolve(file);
            Files.createDirectories(p.getParent());
            Files.writeString(p, text);
            return this;
        }

        String read(String file) throws Exception {
            Path p = data.resolve(file);
            return Files.exists(p) ? Files.readString(p) : null;
        }

        Cloud.Status round() throws Exception {
            in();
            try {
                return cloud.syncNow().get();
            } finally {
                out();
            }
        }

        void textsNow() throws Exception {
            in();
            try {
                cloud.textsNow().get();
            } finally {
                out();
            }
        }

        /** Opened here: this computer's key and the vault's id kept, as opening it with a way in leaves them. */
        Computer opened(Vault.Info vault, Vault.Computer computer) {
            set(VaultParts.ID, vault.id());
            set(VaultParts.KEY, Base64.getEncoder().encodeToString(computer.key()));
            return set(VaultParts.PRIVATE, Base64.getEncoder().encodeToString(computer.pair().getPrivate().getEncoded()));
        }
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

    private Computer withEverything(Computer c) throws Exception {
        return c.set("ui.theme", "DARK").write("writing-style.md", "escribo corto").write("texts/here/" + TEXT, "{}\n")
            .write("pinned-messages.json", "{\"!c\":[]}").write("transcripts/" + TRANSCRIPT, "hola");
    }

    @Test
    void noVaultIsTodaysRound() throws Exception {
        Computer desk = withEverything(new Computer(DESK)).set("server.telegram.apiId", "12345");
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
        Computer desk = withEverything(new Computer(DESK)).set("server.telegram.apiId", "12345");
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
        Computer desk = withEverything(new Computer(DESK)).set(VaultParts.DECLINED, "true");
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
        Computer desk = withEverything(new Computer(DESK)).opened(made.info(), made.computer());
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
        Computer desk = new Computer(DESK).opened(made.info(), made.computer()).set(VaultParts.DECLINED, "true");
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        dav().delete(Vault.ROOT);
        Cloud.Status s = desk.round();
        assertEquals(Cloud.VaultState.NONE, s.vault());
        assertNull(desk.get(VaultParts.ID));
        assertNull(desk.get(VaultParts.KEY));
        assertNull(desk.get(VaultParts.PRIVATE));
        assertTrue(cloud.files.containsKey(SETTINGS), "back to the plain settings");

        Vault.Created again = Vault.create(dav(), OTHER, "Otro");
        cloud.put(Vault.INFO, "{no es una bóveda".getBytes(StandardCharsets.UTF_8));
        desk.opened(again.info(), again.computer());
        Map<String, byte[]> before = files();
        assertEquals(Cloud.VaultState.LOCKED, desk.round().vault(), "a vault file that doesn't read: locked, never forgotten");
        assertEquals(again.info().id(), desk.get(VaultParts.ID));
        assertEquals(Set.of(), written(before).stream().filter(CloudVaultTest::plain).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void anOpenVaultCarriesSecretsSettingsAiTextsPinnedAndTranscripts() throws Exception {
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        Vault.Computer laptopWay = Vault.addComputer(dav(), made.vk(), LAPTOP, "Portátil");
        Computer desk = withEverything(new Computer(DESK)).opened(made.info(), made.computer())
            .set("server.telegram.apiId", "12345").set("server.telegram.apiHash", "0123456789abcdef0123456789abcdef")
            .set("gif.giphy.key", "giphy").set("cloud.dropboxAppKey", "dbx").set("stt.command", "say hola");
        Computer laptop = new Computer(LAPTOP).opened(made.info(), laptopWay).set("ui.theme", "LIGHT")
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
        Computer desk = new Computer(DESK).opened(made.info(), made.computer());
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
        Computer desk = new Computer(DESK).set("ui.theme", "DARK");
        desk.round(); // plain: the account's settings file at its first version
        Vault.Created made = Vault.create(dav(), DESK, "Escritorio");
        Computer laptop = new Computer(LAPTOP).opened(made.info(), Vault.addComputer(dav(), made.vk(), LAPTOP, "Portátil")).set("ui.theme", "LIGHT");
        laptop.round(); // the vault's settings file at its first version too: the same stamp, another file
        assertEquals(cloud.versions.get(SETTINGS), cloud.versions.get(Vault.ROOT + "/Ajustes/unichat-settings.json"));
        desk.opened(made.info(), made.computer());
        assertEquals(Cloud.VaultState.OPEN, desk.round().vault());
        assertEquals("LIGHT", desk.get("ui.theme"), "the vault's settings were read, not taken for the plain ones seen");
    }

    @Test
    void textsAskedAloneGoSealedWithTheVaultOpenAndNowhereWhileLocked() throws Exception {
        Vault.Created made = Vault.create(dav(), OTHER, "Otro");
        Computer desk = withEverything(new Computer(DESK)).set("cloud.media", "false");
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
    void mediaAndTheVaultAreIndependent() throws Exception {
        for (boolean media : new boolean[] {true, false}) {
            for (boolean vault : new boolean[] {true, false}) {
                String what = "media " + media + ", vault " + vault;
                cloud.files.clear();
                cloud.dirs.clear();
                Computer c = withEverything(new Computer(java.util.UUID.randomUUID().toString())).set("cloud.media", Boolean.toString(media));
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
}

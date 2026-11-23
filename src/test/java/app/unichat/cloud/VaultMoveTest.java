package app.unichat.cloud;

import app.unichat.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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

/** What was plain moving into the vault, each plain copy going only once its sealed copy reads back; and the strays after. */
class VaultMoveTest {
    private static final String DESK = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44", LAPTOP = "2c7b4a4f-1e2d-4d1f-8b62-7a3e8d2f3c55",
        OLD = "3d8c5b5a-2f3e-4e2a-9c73-8b4f9e3a4d66";
    private static final String SETTINGS = CloudAccount.SETTINGS_FILE, AI = "Unichat/Settings/unichat-ai.json",
        PHOTO = CloudAccount.MEDIA + "/Ana/a.jpg", NOTES = "Unichat/Settings/notas.txt";
    private static final String TEXT = "3f2a9c1b2d4e 2026-11.jsonl", NEXT = "3f2a9c1b2d4e 2026-12.jsonl", LATER = "3f2a9c1b2d4e 2027-01.jsonl";
    private static final Instant T = Instant.parse("2026-11-19T08:00:00Z");
    private static final List<String> KEYS = List.of(VaultMove.MOVED_AT, VaultMove.QUIET_SINCE, VaultMove.STRAYS_SEEN, VaultMove.RECORD, VaultActions.UNCONFIRMED);

    private FakeDav cloud;
    private Map<String, String> were;
    private Vault.Created vault;
    /** Called before each read of a sealed file: a test's way to break one. */
    private Hook beforeSealedGet = p -> { };

    @FunctionalInterface
    private interface Hook {
        void at(String path) throws IOException;
    }

    @BeforeEach
    void start() throws Exception {
        cloud = new FakeDav("u", "p");
        were = new HashMap<>();
        for (String k : KEYS) {
            were.put(k, Settings.get().getString(k, null));
            Settings.get().set(k, null);
        }
        vault = Vault.create(dav(), DESK, "Escritorio");
        Settings.get().set(VaultMove.RECORD, vault.info().id()); // made here: this computer says who sent plain
    }

    @AfterEach
    void stop() {
        cloud.close();
        were.forEach(Settings.get()::set);
    }

    private Storage dav() {
        return new WebDav(cloud.url(), "u", "p");
    }

    private Storage plain() {
        return dav();
    }

    /** The vault over the account: each read of a sealed file goes through {@link #beforeSealedGet}. */
    private SealedStorage sealed() {
        return new SealedStorage(new Through(dav()) {
            @Override public byte[] get(String path) throws IOException {
                beforeSealedGet.at(path);
                return super.get(path);
            }
        }, () -> vault.vk().clone());
    }

    private VaultMove.Result run(Instant now) throws IOException {
        return VaultMove.run(plain(), sealed(), vault.info().id(), now);
    }

    private void plainFile(String path, String text) {
        put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private void plainBundle(String path, String machine, Instant at, String theme) throws IOException {
        put(path, Bundle.of(machine, at, Map.of("ui.theme", theme), null).toBytes());
    }

    /** Another computer's file, in its folders as a server keeps them. */
    private void put(String path, byte[] data) {
        for (int i = path.indexOf('/'); i > 0; i = path.indexOf('/', i + 1)) cloud.dirs.add(path.substring(0, i));
        cloud.put(path, data);
    }

    /** One computer's plain texts: a file and the index saying its size, as {@link TextSync} sends them. */
    private void plainTexts(String machine, String text) {
        String folder = TextSync.ROOT + "/" + machine;
        plainFile(folder + "/" + TEXT, text);
        plainFile(folder + "/index.json", "{\"files\":{\"" + TEXT + "\":" + text.getBytes(StandardCharsets.UTF_8).length + "}}");
    }

    /** One computer's plain folder as it sends it: these files, and an index of {@code indexed} (what it sent, some gone since). */
    private void plainTexts(String machine, Map<String, String> files, Map<String, String> indexed) throws IOException {
        String folder = TextSync.ROOT + "/" + machine;
        files.forEach((name, text) -> plainFile(folder + "/" + name, text));
        Map<String, Long> index = new HashMap<>();
        indexed.forEach((name, text) -> index.put(name, (long) text.getBytes(StandardCharsets.UTF_8).length));
        put(folder + "/index.json", TextIndex.write(index));
    }

    /** What a computer of the vault sent of its own texts, sealed. */
    private void sealedTexts(String machine, String text) throws IOException {
        String folder = TextSync.ROOT + "/" + machine;
        SealedStorage s = sealed();
        s.put(folder + "/" + TEXT, text.getBytes(StandardCharsets.UTF_8));
        s.put(folder + "/index.json", TextIndex.write(Map.of(TEXT, (long) text.getBytes(StandardCharsets.UTF_8).length)));
    }

    private String opened(String path) throws IOException {
        byte[] b = sealed().get(path);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private Set<String> plainLeft() {
        Set<String> out = new TreeSet<>();
        for (String p : cloud.files.keySet()) if (!p.startsWith(Vault.ROOT + "/")) out.add(p);
        return out;
    }

    @Test
    void aPlainCopyGoesOnlyOnceItsSealedCopyReadsBack() throws Exception {
        plainTexts(LAPTOP, "{\"a\":1}\n");
        String text = TextSync.ROOT + "/" + LAPTOP + "/" + TEXT, sealedText = Vault.ROOT + "/Texts/" + LAPTOP + "/" + TEXT;
        boolean[] failed = {false};
        beforeSealedGet = p -> {
            // The read-back after copying it in: the first time, the network drops.
            if (p.equals(sealedText) && cloud.files.containsKey(sealedText) && !failed[0]) {
                failed[0] = true;
                throw new IOException("se cortó");
            }
        };
        VaultMove.Result first = run(T);
        assertTrue(failed[0], "the read-back was asked");
        assertTrue(cloud.files.containsKey(text), "not read back: the plain copy stays");
        assertFalse(first.moved().contains(text));
        assertTrue(first.error().contains("se cortó"), "and why is told: " + first.error());
        assertNull(Settings.get().getString(VaultMove.MOVED_AT, null), "the move isn't done until everything went");

        VaultMove.Result second = run(T.plusSeconds(60));
        assertFalse(cloud.files.containsKey(text), "read back the next round: it goes");
        assertTrue(second.moved().contains(text));
        assertEquals("{\"a\":1}\n", opened(text));
        assertEquals(List.of(), second.strays(), "what was left of the move is no stray");
        assertNull(second.error());
        assertNotNull(Settings.get().getString(VaultMove.MOVED_AT, null));

        // A sealed copy that doesn't open (damaged) keeps the plain one too.
        Settings.get().set(VaultMove.MOVED_AT, null);
        plainFile(AI, "{\"version\":1}");
        put(Vault.ROOT + "/Settings/unichat-ai.json", "no abre".getBytes(StandardCharsets.UTF_8));
        run(T.plusSeconds(120));
        assertTrue(cloud.files.containsKey(AI), "its sealed copy doesn't open: the plain one stays");
    }

    @Test
    void everythingPlainGoesAndMediaStays() throws Exception {
        plainBundle(SETTINGS, DESK, T.minusSeconds(60), "DARK");
        plainBundle(AI, DESK, T.minusSeconds(60), "x");
        plainFile("Unichat/Settings/unichat-pinned.json", "{\"version\":1}");
        plainFile(NOTES, "not ours");
        plainFile(PHOTO, "foto");
        plainTexts(DESK, "{\"mio\":1}\n");
        plainTexts(LAPTOP, "{\"suyo\":1}\n");
        plainFile(TextSync.ROOT + "/" + LAPTOP + "/rm -rf.jsonl", "not ours");
        plainFile(TextSync.ROOT + "/no-es-una-maquina/" + TEXT, "not ours");
        // The round already wrote the vault's settings (newer): the plain ones are only checked, never copied over it.
        SealedStorage s = sealed();
        s.put(SETTINGS, Bundle.of(DESK, T, Map.of("ui.theme", "LIGHT"), null).toBytes());
        sealedTexts(DESK, "{\"mio\":1}\n"); // this computer's own, sent again sealed by its round (only it writes them)

        VaultMove.Result r = run(T);
        assertEquals(new TreeSet<>(Set.of(NOTES, PHOTO, TextSync.ROOT + "/" + LAPTOP + "/rm -rf.jsonl",
            TextSync.ROOT + "/no-es-una-maquina/" + TEXT)), plainLeft(), "only media and what isn't ours stay plain");
        assertEquals(7, r.moved().size(), "each plain file of ours: " + r.moved());
        assertEquals("LIGHT", Bundle.parse(s.get(SETTINGS)).settings().get("ui.theme"), "the vault's newer settings kept");
        assertEquals("x", Bundle.parse(s.get(AI)).settings().get("ui.theme"), "a part the vault lacked came in");
        assertEquals("{\"version\":1}", opened("Unichat/Settings/unichat-pinned.json"));
        assertEquals("{\"suyo\":1}\n", opened(TextSync.ROOT + "/" + LAPTOP + "/" + TEXT), "a computer without the vault's are brought in");
        assertTrue(opened(TextSync.ROOT + "/" + LAPTOP + "/index.json").contains(TEXT), "and its index says so, for the others");
        assertEquals(T.toString(), Settings.get().getString(VaultMove.MOVED_AT, null));

        int puts = cloud.puts.get(), gets = cloud.gets.get();
        VaultMove.Result again = run(T.plusSeconds(60));
        assertEquals(List.of(), again.moved());
        assertEquals(List.of(), again.strays());
        assertEquals(puts, cloud.puts.get(), "nothing plain: nothing to do");
        assertEquals(gets, cloud.gets.get(), "not even a read");
    }

    @Test
    void anOlderComputerSendingPlainIsNamed() throws Exception {
        String first = "{\"viejo\":1}\n";
        plainTexts(OLD, first); // sending plain when the move started
        run(T); // moved in: the move done
        Vault.addComputer(dav(), vault.vk(), LAPTOP, "Portátil");
        SealedStorage s = sealed();
        s.put(SETTINGS, Bundle.of(DESK, T, Map.of("ui.theme", "LIGHT"), null).toBytes());
        plainBundle(SETTINGS, LAPTOP, T.plusSeconds(30), "DARK"); // newer, from a computer still without the vault
        String next = "{\"nuevo\":1}\n"; // still on a Unichat without the vault: a new month goes up plain
        plainTexts(OLD, Map.of(NEXT, next), Map.of(TEXT, first, NEXT, next));

        VaultMove.Result r = run(T.plusSeconds(60));
        assertEquals(List.of(), r.moved(), "a stray's plain copies aren't deleted over and over");
        assertEquals(List.of(new VaultMove.Stray(LAPTOP, T.plusSeconds(30)), new VaultMove.Stray(OLD, T.plusSeconds(60))), r.strays());
        assertTrue(cloud.files.containsKey(SETTINGS));
        assertEquals("LIGHT", Bundle.parse(s.get(SETTINGS)).settings().get("ui.theme"), "a stray's settings never over the vault's");
        assertEquals(next, opened(TextSync.ROOT + "/" + OLD + "/" + NEXT), "its new texts come in all the same: nothing lost");
        assertEquals(first, opened(TextSync.ROOT + "/" + OLD + "/" + TEXT));
        assertEquals(Map.of(LAPTOP, "Portátil", OLD, ""), VaultMove.names(dav(), r.strays()), "named from its computer slot, or unknown");

        String later = "{\"luego\":1}\n";
        plainTexts(OLD, Map.of(NEXT, next, LATER, later), Map.of(TEXT, first, NEXT, next, LATER, later)); // it goes on writing
        run(T.plusSeconds(120));
        assertEquals(later, opened(TextSync.ROOT + "/" + OLD + "/" + LATER), "what's new comes in too");
        int[] reads = {0};
        beforeSealedGet = p -> { if (p.contains(OLD)) reads[0]++; };
        run(T.plusSeconds(180));
        assertEquals(0, reads[0], "nothing new: its texts aren't read again");
    }

    @Test
    void strayCopiesGoOnceItsQuietForADay() throws Exception {
        String first = "{\"viejo\":1}\n", next = "{\"nuevo\":1}\n", later = "{\"luego\":1}\n";
        plainTexts(OLD, first);
        run(T);
        plainBundle(SETTINGS, LAPTOP, T, "DARK");
        plainTexts(OLD, Map.of(NEXT, next), Map.of(TEXT, first, NEXT, next));
        Instant start = T.plusSeconds(60);
        run(start);
        assertEquals(start.toString(), Settings.get().getString(VaultMove.QUIET_SINCE, null));
        run(start.plus(Duration.ofHours(12)));
        plainTexts(OLD, Map.of(NEXT, next, LATER, later), Map.of(TEXT, first, NEXT, next, LATER, later)); // something new halfway
        Instant changed = start.plus(Duration.ofHours(13));
        run(changed);
        assertEquals(changed.toString(), Settings.get().getString(VaultMove.QUIET_SINCE, null));
        run(start.plus(VaultMove.QUIET));
        assertTrue(cloud.files.containsKey(SETTINGS), "a day since the first, not since the last: still there");

        VaultMove.Result quiet = run(changed.plus(VaultMove.QUIET));
        assertEquals(Set.of(), plainLeft(), "a day without anything new: they go");
        String folder = TextSync.ROOT + "/" + OLD;
        assertEquals(new TreeSet<>(Set.of(SETTINGS, folder + "/" + NEXT, folder + "/" + LATER, folder + "/index.json")), new TreeSet<>(quiet.moved()));
        assertEquals(List.of(), quiet.strays());
        assertNull(Settings.get().getString(VaultMove.QUIET_SINCE, null));
        assertEquals(later, opened(folder + "/" + LATER), "kept, sealed");
    }

    @Test
    void aFolderThatCameAfterTheMoveStartedIsNamedButNeverTaken() throws Exception {
        run(T); // nothing plain: the move done, nobody sending plain
        plainTexts(OLD, "{\"puesto\":1}\n"); // through the account, under a new machine's name
        VaultMove.Result r = run(T.plusSeconds(60));
        assertEquals(List.of(new VaultMove.Stray(OLD, T.plusSeconds(60))), r.strays(), "named");
        assertNull(opened(TextSync.ROOT + "/" + OLD + "/" + TEXT), "never sealed in");
        assertNull(opened(TextSync.ROOT + "/" + OLD + "/index.json"));
        run(T.plus(VaultMove.QUIET).plusSeconds(120));
        assertTrue(cloud.files.containsKey(TextSync.ROOT + "/" + OLD + "/" + TEXT), "nor deleted: it was never ours to take");
    }

    @Test
    void aLongerPlainFileNeverReplacesASealedOne() throws Exception {
        String first = "{\"viejo\":1}\n", longer = "{\"viejo\":1}\n{\"puesto\":2}\n";
        plainTexts(OLD, first);
        run(T);
        assertEquals(first, opened(TextSync.ROOT + "/" + OLD + "/" + TEXT));
        plainTexts(OLD, longer);
        run(T.plusSeconds(60));
        run(T.plus(VaultMove.QUIET).plusSeconds(120));
        assertEquals(first, opened(TextSync.ROOT + "/" + OLD + "/" + TEXT), "the sealed one is never written over");
        assertTrue(cloud.files.containsKey(TextSync.ROOT + "/" + OLD + "/" + TEXT), "and the plain one kept: nothing lost");
    }

    @Test
    void aPlainFileRewrittenWhileItMovesStays() throws Exception {
        plainTexts(OLD, "{\"viejo\":1}\n");
        String text = TextSync.ROOT + "/" + OLD + "/" + TEXT, longer = "{\"viejo\":1}\n{\"viejo\":2}\n";
        int[] stamps = {0};
        Storage plain = new Through(dav()) {
            @Override public String stamp(String path) throws IOException {
                // Asked once before reading it and once right before deleting it: the older computer sends a longer one between.
                if (path.equals(text) && ++stamps[0] == 2) plainFile(text, longer);
                return super.stamp(path);
            }
        };
        VaultMove.run(plain, sealed(), vault.info().id(), T);
        assertEquals(2, stamps[0]);
        assertArrayEquals(longer.getBytes(StandardCharsets.UTF_8), cloud.files.get(text), "the longer one isn't lost");
        assertNull(Settings.get().getString(VaultMove.MOVED_AT, null), "nor the move done");
        VaultMove.run(plain(), sealed(), vault.info().id(), T.plusSeconds(60));
        assertEquals("{\"viejo\":1}\n", opened(text), "never written over the sealed one");
        assertArrayEquals(longer.getBytes(StandardCharsets.UTF_8), cloud.files.get(text), "so it stays plain: still not lost");
    }

    @Test
    void aComputerOfTheVaultIsTheOnlyWriterOfItsTextsAndOnlyItsCopyLetsThePlainGo() throws Exception {
        Vault.addComputer(dav(), vault.vk(), LAPTOP, "Portátil");
        String text = TextSync.ROOT + "/" + LAPTOP + "/" + TEXT, longer = "{\"suyo\":1}\n{\"suyo\":2}\n";
        plainTexts(LAPTOP, "{\"suyo\":1}\n");
        sealedTexts(LAPTOP, "{}\n"); // it hasn't sent all of it sealed yet
        int versions = cloud.versions.get(Vault.ROOT + "/Texts/" + LAPTOP + "/" + TEXT);
        VaultMove.Result r = run(T);
        assertNull(r.error());
        assertTrue(cloud.files.containsKey(text), "its sealed copy is shorter: the plain one stays");
        assertEquals("{}\n", opened(text), "and the move never writes its texts");
        assertEquals(versions, cloud.versions.get(Vault.ROOT + "/Texts/" + LAPTOP + "/" + TEXT));
        assertNull(Settings.get().getString(VaultMove.MOVED_AT, null), "the move waits for it");

        boolean[] sent = {false};
        beforeSealedGet = p -> {
            if (p.endsWith(LAPTOP + "/index.json") && !sent[0]) { // the laptop sends its own while the move looks
                sent[0] = true;
                sealedTexts(LAPTOP, longer);
            }
        };
        run(T.plusSeconds(60));
        assertTrue(sent[0]);
        assertEquals(longer, opened(text), "the longer one it sent survives");
        assertFalse(cloud.files.containsKey(text), "and, as long as the plain one, lets it go");
        assertFalse(cloud.files.containsKey(TextSync.ROOT + "/" + LAPTOP + "/index.json"));
    }

    @Test
    void aPlainCopyIsNeverDeletedOnceTheVaultIsGone() throws Exception {
        plainTexts(LAPTOP, "{\"a\":1}\n");
        String text = TextSync.ROOT + "/" + LAPTOP + "/" + TEXT;
        int[] stamps = {0};
        Storage plain = new Through(dav()) {
            @Override public String stamp(String path) throws IOException {
                // The vault turned off by another computer right as the plain copy is about to go (its stamp read again).
                if (path.equals(text) && ++stamps[0] == 2) cloud.files.remove(Vault.INFO);
                return super.stamp(path);
            }
        };
        VaultMove.run(plain, sealed(), vault.info().id(), T);
        assertTrue(cloud.files.containsKey(text), "the plain copy is the only one left: kept");
    }

    @Test
    void noPlainCopyGoesWhileTheCodeIsntKept() throws Exception {
        plainBundle(SETTINGS, OLD, T, "DARK");
        plainTexts(OLD, "{\"viejo\":1}\n");
        Settings.get().set(VaultActions.UNCONFIRMED, vault.info().id()); // the code shown, never confirmed
        VaultMove.Result r = run(T);
        assertEquals(List.of(), r.moved(), "nothing deleted: without the code, the vault may be lost with its only computer");
        assertTrue(cloud.files.containsKey(SETTINGS));
        assertTrue(cloud.files.containsKey(TextSync.ROOT + "/" + OLD + "/" + TEXT));
        assertEquals("{\"viejo\":1}\n", opened(TextSync.ROOT + "/" + OLD + "/" + TEXT), "though copied in");
        assertNull(Settings.get().getString(VaultMove.MOVED_AT, null), "nor the move done");

        Settings.get().set(VaultActions.UNCONFIRMED, null); // kept
        run(T.plusSeconds(60));
        assertEquals(Set.of(), plainLeft(), "then they go");
    }
}

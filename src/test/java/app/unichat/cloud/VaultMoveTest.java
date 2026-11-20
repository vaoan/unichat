package app.unichat.cloud;

import app.unichat.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    private static final String SETTINGS = CloudAccount.SETTINGS_FILE, AI = "Unichat/Ajustes/unichat-ai.json",
        PHOTO = CloudAccount.MEDIA + "/Ana/a.jpg", NOTES = "Unichat/Ajustes/notas.txt";
    private static final String TEXT = "3f2a9c1b2d4e 2026-11.jsonl";
    private static final Instant T = Instant.parse("2026-11-19T08:00:00Z");
    private static final List<String> KEYS = List.of(VaultMove.MOVED_AT, VaultMove.QUIET_SINCE, VaultMove.STRAYS_SEEN);

    private FakeDav cloud;
    private Map<String, String> were;
    private Vault.Created vault;
    /** Called before each read of a sealed file: a test's way to break one. */
    private Hook beforeSealedGet = p -> { };

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
        return VaultMove.run(plain(), sealed(), now);
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
        String text = TextSync.ROOT + "/" + LAPTOP + "/" + TEXT, sealedText = Vault.ROOT + "/Textos/" + LAPTOP + "/" + TEXT;
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
        assertNull(Settings.get().getString(VaultMove.MOVED_AT, null), "the move isn't done until everything went");

        VaultMove.Result second = run(T.plusSeconds(60));
        assertFalse(cloud.files.containsKey(text), "read back the next round: it goes");
        assertTrue(second.moved().contains(text));
        assertEquals("{\"a\":1}\n", opened(text));
        assertEquals(List.of(), second.strays(), "what was left of the move is no stray");
        assertNotNull(Settings.get().getString(VaultMove.MOVED_AT, null));

        // A sealed copy that doesn't open (damaged) keeps the plain one too.
        Settings.get().set(VaultMove.MOVED_AT, null);
        plainFile(AI, "{\"version\":1}");
        put(Vault.ROOT + "/Ajustes/unichat-ai.json", "no abre".getBytes(StandardCharsets.UTF_8));
        run(T.plusSeconds(120));
        assertTrue(cloud.files.containsKey(AI), "its sealed copy doesn't open: the plain one stays");
    }

    @Test
    void everythingPlainGoesAndMediaStays() throws Exception {
        plainBundle(SETTINGS, DESK, T.minusSeconds(60), "DARK");
        plainBundle(AI, DESK, T.minusSeconds(60), "x");
        plainFile("Unichat/Ajustes/unichat-pinned.json", "{\"version\":1}");
        plainFile(NOTES, "not ours");
        plainFile(PHOTO, "foto");
        plainTexts(DESK, "{\"mio\":1}\n");
        plainTexts(LAPTOP, "{\"suyo\":1}\n");
        plainFile(TextSync.ROOT + "/" + LAPTOP + "/rm -rf.jsonl", "not ours");
        plainFile(TextSync.ROOT + "/no-es-una-maquina/" + TEXT, "not ours");
        // The round already wrote the vault's settings (newer): the plain ones are only checked, never copied over it.
        SealedStorage s = sealed();
        s.put(SETTINGS, Bundle.of(DESK, T, Map.of("ui.theme", "LIGHT"), null).toBytes());

        VaultMove.Result r = run(T);
        assertEquals(new TreeSet<>(Set.of(NOTES, PHOTO, TextSync.ROOT + "/" + LAPTOP + "/rm -rf.jsonl",
            TextSync.ROOT + "/no-es-una-maquina/" + TEXT)), plainLeft(), "only media and what isn't ours stay plain");
        assertEquals(7, r.moved().size(), "each plain file of ours: " + r.moved());
        assertEquals("LIGHT", Bundle.parse(s.get(SETTINGS)).settings().get("ui.theme"), "the vault's newer settings kept");
        assertEquals("x", Bundle.parse(s.get(AI)).settings().get("ui.theme"), "a part the vault lacked came in");
        assertEquals("{\"version\":1}", opened("Unichat/Ajustes/unichat-pinned.json"));
        assertEquals("{\"mio\":1}\n", opened(TextSync.ROOT + "/" + DESK + "/" + TEXT), "this computer's texts sent plain: no need to send again");
        assertEquals("{\"suyo\":1}\n", opened(TextSync.ROOT + "/" + LAPTOP + "/" + TEXT), "another computer's too");
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
        run(T); // nothing plain: the move is done
        Vault.addComputer(dav(), vault.vk(), LAPTOP, "Portátil");
        SealedStorage s = sealed();
        s.put(SETTINGS, Bundle.of(DESK, T, Map.of("ui.theme", "LIGHT"), null).toBytes());
        plainBundle(SETTINGS, LAPTOP, T.plusSeconds(30), "DARK"); // newer, from a computer still without the vault
        plainTexts(OLD, "{\"viejo\":1}\n");

        VaultMove.Result r = run(T.plusSeconds(60));
        assertEquals(List.of(), r.moved(), "a stray's plain copies aren't deleted over and over");
        assertEquals(List.of(new VaultMove.Stray(LAPTOP, T.plusSeconds(30)), new VaultMove.Stray(OLD, T.plusSeconds(60))), r.strays());
        assertTrue(cloud.files.containsKey(SETTINGS));
        assertEquals("LIGHT", Bundle.parse(s.get(SETTINGS)).settings().get("ui.theme"), "a stray's settings never over the vault's");
        assertEquals("{\"viejo\":1}\n", opened(TextSync.ROOT + "/" + OLD + "/" + TEXT), "its texts come in all the same: nothing lost");
        assertEquals(Map.of(LAPTOP, "Portátil", OLD, ""), VaultMove.names(dav(), r.strays()), "named from its computer slot, or unknown");

        plainTexts(OLD, "{\"viejo\":1}\n{\"viejo\":2}\n"); // it goes on writing
        run(T.plusSeconds(120));
        assertEquals("{\"viejo\":1}\n{\"viejo\":2}\n", opened(TextSync.ROOT + "/" + OLD + "/" + TEXT), "what grew comes in too");
        int gets = cloud.gets.get();
        run(T.plusSeconds(180));
        assertTrue(cloud.gets.get() - gets <= 4, "nothing new: its texts aren't read again (" + (cloud.gets.get() - gets) + " reads)");
    }

    @Test
    void strayCopiesGoOnceItsQuietForADay() throws Exception {
        run(T);
        plainBundle(SETTINGS, LAPTOP, T, "DARK");
        plainTexts(OLD, "{\"viejo\":1}\n");
        Instant first = T.plusSeconds(60);
        run(first);
        assertEquals(first.toString(), Settings.get().getString(VaultMove.QUIET_SINCE, null));
        run(first.plus(Duration.ofHours(12)));
        plainTexts(OLD, "{\"viejo\":1}\n{\"viejo\":2}\n"); // something new halfway: the day starts again
        Instant changed = first.plus(Duration.ofHours(13));
        run(changed);
        assertEquals(changed.toString(), Settings.get().getString(VaultMove.QUIET_SINCE, null));
        run(first.plus(VaultMove.QUIET));
        assertTrue(cloud.files.containsKey(SETTINGS), "a day since the first, not since the last: still there");

        VaultMove.Result quiet = run(changed.plus(VaultMove.QUIET));
        assertEquals(Set.of(), plainLeft(), "a day without anything new: they go");
        assertEquals(new TreeSet<>(Set.of(SETTINGS, TextSync.ROOT + "/" + OLD + "/" + TEXT, TextSync.ROOT + "/" + OLD + "/index.json")),
            new TreeSet<>(quiet.moved()));
        assertEquals(List.of(), quiet.strays());
        assertNull(Settings.get().getString(VaultMove.QUIET_SINCE, null));
        assertEquals("{\"viejo\":1}\n{\"viejo\":2}\n", opened(TextSync.ROOT + "/" + OLD + "/" + TEXT), "kept, sealed");
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
        VaultMove.run(plain, sealed(), T);
        assertEquals(2, stamps[0]);
        assertArrayEquals(longer.getBytes(StandardCharsets.UTF_8), cloud.files.get(text), "the longer one isn't lost");
        assertNull(Settings.get().getString(VaultMove.MOVED_AT, null), "nor the move done");
        VaultMove.run(plain(), sealed(), T.plusSeconds(60));
        assertEquals(longer, opened(text), "it comes in the next round");
        assertFalse(cloud.files.containsKey(text));
    }

    /** A storage passing everything on, to override one call. */
    private static class Through implements Storage {
        private final Storage to;

        Through(Storage to) {
            this.to = to;
        }

        @Override public void check() throws IOException { to.check(); }
        @Override public boolean exists(String path) throws IOException { return to.exists(path); }
        @Override public String stamp(String path) throws IOException { return to.stamp(path); }
        @Override public void mkdirs(String dir) throws IOException { to.mkdirs(dir); }
        @Override public List<String> list(String dir) throws IOException { return to.list(dir); }
        @Override public void delete(String path) throws IOException { to.delete(path); }
        @Override public void put(String path, Path file) throws IOException { to.put(path, file); }
        @Override public void put(String path, byte[] data) throws IOException { to.put(path, data); }
        @Override public byte[] get(String path) throws IOException { return to.get(path); }
        @Override public boolean get(String path, Path target) throws IOException { return to.get(path, target); }
    }
}

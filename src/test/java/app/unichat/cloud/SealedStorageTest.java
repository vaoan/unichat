package app.unichat.cloud;

import app.unichat.cloud.SyncedPart.Action;
import app.unichat.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SealedStorageTest {
    private static final String PATH = "Unichat/Ajustes/prueba.json", STORED = Vault.ROOT + "/Ajustes/prueba.json";
    private static final Instant T = Instant.parse("2026-11-10T08:00:00Z");
    private static final String DESK = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44", LAPTOP = "2c7b4a4f-1e2d-4d1f-8b62-7a3e8d2f3c55";
    private final byte[] vk = VaultCrypto.randomKey();

    @TempDir
    Path dir;

    @AfterEach
    void forget() {
        for (String c : new String[] {"a", "b"}) Settings.get().set("cloud.base.test-sealed-" + c, null);
    }

    private SealedStorage over(Storage s) {
        return new SealedStorage(s, () -> vk.clone());
    }

    @Test
    void whatGoesInIsUnreadableThereAndTheSameHere() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            WebDav dav = new WebDav(cloud.url(), "u", "p");
            SealedStorage sealed = over(dav);
            byte[] secret = "ui.theme=DARK and more words".getBytes(StandardCharsets.UTF_8);
            sealed.mkdirs("Unichat/Ajustes");
            sealed.put(PATH, secret);

            byte[] there = cloud.files.get(STORED);
            assertFalse(new String(there, StandardCharsets.ISO_8859_1).contains("DARK"), "unreadable in the account");
            assertFalse(cloud.files.containsKey(PATH), "kept under the vault's folder");
            assertArrayEquals(secret, sealed.get(PATH));
            assertTrue(sealed.exists(PATH));
            assertEquals(List.of("prueba.json"), sealed.list("Unichat/Ajustes"));
            assertEquals(-1, sealed.info(PATH).size());
            assertNull(sealed.info("Unichat/Ajustes/otra.json"));
            assertNull(sealed.get("Unichat/Ajustes/otra.json"));
            assertFalse(sealed.get("Unichat/Ajustes/otra.json", dir.resolve("x")));
            assertFalse(Files.exists(dir.resolve("x")));

            Path file = Files.write(dir.resolve("in"), secret), out = dir.resolve("out");
            AtomicLong reported = new AtomicLong();
            sealed.put("Unichat/Ajustes/dos.json", file, reported::set);
            assertEquals(there.length, reported.get(), "the sealed size, once");
            assertTrue(sealed.get("Unichat/Ajustes/dos.json", out));
            assertArrayEquals(secret, Files.readAllBytes(out));

            assertNotEquals(sealed.stamp(PATH), (Object) null);
            sealed.delete("Unichat/Ajustes/dos.json");
            assertFalse(sealed.exists("Unichat/Ajustes/dos.json"));
            sealed.check();
        }
    }

    @Test
    void whatTheVaultDoesntKeepIsRefused() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            SealedStorage sealed = over(new WebDav(cloud.url(), "u", "p"));
            for (String path : new String[] {"Otro/x", "Unichat", Vault.ROOT + "/x", CloudAccount.MEDIA + "/a.mp4", CloudAccount.MEDIA, "../x"}) {
                assertThrows(IllegalArgumentException.class, () -> sealed.put(path, new byte[1]), path);
                assertThrows(IllegalArgumentException.class, () -> sealed.get(path), path);
            }
            assertTrue(cloud.files.isEmpty());
        }
    }

    @Test
    void aFileMovedToAnotherNameDoesntOpen() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            SealedStorage sealed = over(new WebDav(cloud.url(), "u", "p"));
            sealed.put(PATH, "x".getBytes(StandardCharsets.UTF_8));
            cloud.files.put(Vault.ROOT + "/Ajustes/copia.json", cloud.files.get(STORED));
            IOException e = assertThrows(IOException.class, () -> sealed.get("Unichat/Ajustes/copia.json"));
            assertEquals(app.unichat.support.I18n.t("cloud.error.vaultDamaged"), e.getMessage());
        }
    }

    @Test
    void aDamagedFileFailsWithoutLeavingAPart() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            SealedStorage sealed = over(new WebDav(cloud.url(), "u", "p"));
            sealed.put(PATH, "hello".getBytes(StandardCharsets.UTF_8));
            byte[] bad = cloud.files.get(STORED).clone();
            bad[bad.length - 1] ^= 1;
            cloud.files.put(STORED, bad);

            IOException e = assertThrows(IOException.class, () -> sealed.get(PATH));
            assertEquals(app.unichat.support.I18n.t("cloud.error.vaultDamaged"), e.getMessage());
            Path target = dir.resolve("target");
            assertThrows(IOException.class, () -> sealed.get(PATH, target));
            try (var files = Files.list(dir)) {
                assertEquals(List.of(), files.filter(p -> p.getFileName().toString().startsWith("target") || p.getFileName().toString().startsWith(".target")).toList());
            }
            byte[] otherKey = VaultCrypto.randomKey();
            assertThrows(IOException.class, () -> new SealedStorage(new WebDav(cloud.url(), "u", "p"), () -> otherKey).get(PATH), "another key opens nothing");
        }
    }

    @Test
    void syncedPartRunsOverItUnchanged() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            SealedStorage sealed = over(new WebDav(cloud.url(), "u", "p"));
            Map<String, String> sa = new TreeMap<>(), sb = new TreeMap<>();
            SyncedPart a = new SyncedPart(PATH, "test-sealed-a", now -> Bundle.of("a", now, sa, null), t -> { sa.clear(); sa.putAll(t.settings()); });
            SyncedPart b = new SyncedPart(PATH, "test-sealed-b", now -> Bundle.of("b", now, sb, null), t -> { sb.clear(); sb.putAll(t.settings()); });
            sa.put("ui.theme", "DARK");
            assertEquals(Action.UPLOAD, a.sync(sealed, T));
            assertEquals(Action.ADOPT, b.sync(sealed, T));
            assertEquals("DARK", sb.get("ui.theme"));
            sb.put("ui.accent", "#FF0000");
            assertEquals(Action.UPLOAD, b.sync(sealed, T.plusSeconds(60)));
            assertEquals(Action.ADOPT, a.sync(sealed, T.plusSeconds(60)));
            assertEquals("#FF0000", sa.get("ui.accent"));

            String before = sealed.stamp(PATH);
            sealed.put(PATH, sealed.get(PATH)); // sealed again: a new nonce, a new stamp, the same content
            assertNotEquals(before, sealed.stamp(PATH));
            int puts = cloud.puts.get();
            assertEquals(Action.NOTHING, a.sync(sealed, T.plusSeconds(120)));
            assertEquals(Action.NOTHING, b.sync(sealed, T.plusSeconds(120)));
            assertEquals(puts, cloud.puts.get(), "nothing goes up again");
        }
    }

    @Test
    void textSyncRunsOverItUnchanged() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            SealedStorage sealed = over(new WebDav(cloud.url(), "u", "p"));
            String file = "3f2a9c1b2d4e 2026-11.jsonl";
            for (String m : new String[] {DESK, LAPTOP}) {
                Files.writeString(Files.createDirectories(dir.resolve(m + "/texts/here")).resolve(file), "{\"id\":\"" + m + "\"}\n");
            }
            TextSync desk = new TextSync(dir.resolve(DESK + "/texts"), dir.resolve(DESK + "/ct.json"), DESK);
            TextSync laptop = new TextSync(dir.resolve(LAPTOP + "/texts"), dir.resolve(LAPTOP + "/ct.json"), LAPTOP);
            assertEquals(Set.of(), desk.run(sealed));
            assertEquals(Set.of("3f2a9c1b2d4e"), laptop.run(sealed));
            assertEquals(Set.of("3f2a9c1b2d4e"), desk.run(sealed));
            assertEquals("{\"id\":\"" + DESK + "\"}\n", Files.readString(dir.resolve(LAPTOP + "/texts/" + DESK + "/" + file)));
            assertEquals("{\"id\":\"" + LAPTOP + "\"}\n", Files.readString(dir.resolve(DESK + "/texts/" + LAPTOP + "/" + file)));
            assertTrue(cloud.files.keySet().stream().allMatch(p -> p.startsWith(Vault.ROOT + "/")), "all of it inside the vault's folder");
        }
    }
}

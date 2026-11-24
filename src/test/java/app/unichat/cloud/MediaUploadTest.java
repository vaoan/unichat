package app.unichat.cloud;

import app.unichat.Present;
import app.unichat.support.PathParts;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What chats saved goes up until all of it is there, checked; what differs is repaired, the bigger copy winning. */
class MediaUploadTest {
    @TempDir
    Path dir;

    /** An account in memory that gives SHA-1s like pCloud, and can fail or damage on purpose. */
    static final class Account implements Storage {
        final Map<String, byte[]> files = new HashMap<>();
        final Set<String> failing = new HashSet<>();
        final Set<String> damaging = new HashSet<>();
        /** Each path whose copy was looked at ({@link #info}), in order. */
        final List<String> looked = new java.util.ArrayList<>();

        @Override public void check() { /* the fake ignores it */ }
        @Override public boolean exists(String path) { return files.containsKey(path); }
        @Override public void delete(String path) { files.remove(path); }
        @Override public @Nullable String stamp(String path) { return null; }
        @Override public void mkdirs(String d) { /* the fake ignores it */ }

        @Override public void move(String from, String to) throws IOException {
            if (files.keySet().stream().anyMatch(p -> p.equals(to) || p.startsWith(to + "/"))) throw new IOException("already there");
            for (String p : List.copyOf(files.keySet())) {
                if (p.equals(from) || p.startsWith(from + "/")) files.put(to + p.substring(from.length()), files.remove(p));
            }
        }

        @Override public List<String> list(String d) {
            return files.keySet().stream().filter(p -> p.startsWith(d + "/")).map(p -> p.substring(d.length() + 1).split("/")[0])
                    .distinct().toList();
        }

        @Override public void put(String path, Path file) throws IOException {
            put(path, Files.readAllBytes(file));
        }

        @Override public void put(String path, byte[] data) throws IOException {
            if (failing.stream().anyMatch(path::endsWith)) throw new IOException("the network went away");
            byte[] stored = damaging.stream().anyMatch(path::endsWith) ? java.util.Arrays.copyOf(data, data.length / 2) : data;
            files.put(path, stored);
        }

        /** Only small files (the settings) come down whole: media comes down into a file (a 3 GB video didn't fit). */
        @Override public byte[] get(String path) {
            throw new AssertionError("media read whole into memory: " + path);
        }

        @Override public boolean get(String path, Path target) throws IOException {
            byte[] f = files.get(path);
            if (f == null) return false;
            Files.write(target, f);
            return true;
        }

        @Override public @Nullable RemoteFile info(String path) throws IOException {
            looked.add(path);
            byte[] f = files.get(path);
            if (f == null) return null;
            Path tmp = Files.createTempFile("sum", ".bin");
            try {
                Files.write(tmp, f);
                return new RemoteFile(f.length, Checksums.SHA1, Checksums.of(tmp, Checksums.SHA1));
            } finally {
                Files.delete(tmp);
            }
        }
    }

    private Instant now = Instant.parse("2026-11-10T12:00:00Z");

    private MediaUpload uploader(Path chat) {
        return new MediaUpload(dir.resolve("cloud-uploaded.txt"), () -> List.of(chat), p -> PathParts.name(p).startsWith("Chat ("),
                () -> now);
    }

    private Path chat(String... files) throws IOException {
        Path c = Files.createDirectories(dir.resolve("Ana"));
        for (String f : files) Files.writeString(c.resolve(f), "contenido de " + f);
        return c;
    }

    private static String remote(String name) {
        return CloudAccount.MEDIA + "/Ana/" + name;
    }

    @Test
    void aFileMadeFromWhatTheCloudHasAlreadyDoesntGoUp() throws Exception {
        Account acc = new Account();
        Path c = chat("a.jpg", "Chat (WhatsApp) 3f2a9c1b2d4e.txt");
        assertEquals(1, uploader(c).run(acc, (d, t, n) -> { }, () -> true));
        assertEquals(Set.of(remote("a.jpg")), acc.files.keySet(), "the texts' copy stays here: it grows, and its source goes up");
    }

    @Test
    void aFailureWaitsItsTurnWithoutHoldingTheOthersBackAndNeverGivesUp() throws Exception {
        Account acc = new Account();
        Path c = chat("a.jpg", "b.jpg", "c.jpg");
        acc.failing.add("b.jpg");
        MediaUpload up = uploader(c);
        assertEquals(2, up.run(acc, (d, t, n) -> { }, () -> true), "a and c went up though b failed");
        assertEquals(1, up.retrying());
        assertEquals(now.plus(Duration.ofMinutes(1)), up.nextRetry(), "first wait: a minute");

        assertEquals(0, up.run(acc, (d, t, n) -> { }, () -> true), "not yet its turn");
        for (int i = 0; i < 10; i++) {
            now = Present.of(up.nextRetry());
            up.run(acc, (d, t, n) -> { }, () -> true);
        }
        assertEquals(1, up.retrying(), "still trying, never given up");
        assertEquals(now.plus(MediaUpload.LONGEST_WAIT), up.nextRetry(), "the wait stops growing at an hour");

        acc.failing.clear();
        now = Present.of(up.nextRetry());
        assertEquals(1, up.run(acc, (d, t, n) -> { }, () -> true));
        assertEquals(0, up.retrying());
        assertEquals(0, up.waiting(), "all of it is there");
    }

    /** Yes {@code times} times, then no: a "may go on" that changes its mind as the files go. */
    private static java.util.function.BooleanSupplier yes(int times) {
        int[] left = {times};
        return () -> left[0]-- > 0;
    }

    @Test
    void itAsksBeforeEachFileAndTheRestWaitForTheNextRound() throws Exception {
        Account acc = new Account();
        Path c = chat("a.jpg", "b.jpg", "c.jpg");
        MediaUpload up = uploader(c);
        assertEquals(0, up.run(acc, (d, t, n) -> { }, yes(0)), "no from the start: nothing goes");
        assertEquals(1, up.run(acc, (d, t, n) -> { }, yes(1)), "the first went, then no");
        assertEquals(Set.of(remote("a.jpg")), acc.files.keySet());
        assertEquals(2, up.waiting(), "the rest wait, nothing given up");
        assertEquals(0, up.retrying(), "and none counts as failed");
        assertEquals(2, up.run(acc, (d, t, n) -> { }, () -> true));
    }

    @Test
    void theCheckAsksBeforeEachFileToo() throws Exception {
        Account acc = new Account();
        Path c = chat("a.jpg", "b.jpg");
        MediaUpload up = uploader(c);
        up.run(acc, (d, t, n) -> { }, () -> true);
        acc.files.put(remote("a.jpg"), "co".getBytes(StandardCharsets.UTF_8));
        acc.files.put(remote("b.jpg"), "co".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, up.verify(acc, (d, t, n) -> { }, yes(1)), "the first repaired, then no");
        assertEquals("co", new String(acc.files.get(remote("b.jpg")), StandardCharsets.UTF_8), "the second waits");
        assertEquals(1, up.verify(acc, (d, t, n) -> { }, () -> true));
    }

    @Test
    void aCheckCutShortGoesOnFromWhereItStopped() throws Exception {
        Account acc = new Account();
        Path c = chat("a.jpg", "b.jpg", "c.jpg");
        MediaUpload up = uploader(c);
        up.run(acc, (d, t, n) -> { }, () -> true);
        assertEquals(0, up.verify(acc, (d, t, n) -> { }, yes(1)), "a checked, then no");
        acc.looked.clear();
        up.verify(acc, (d, t, n) -> { }, yes(1));
        assertEquals(List.of(remote("b.jpg")), acc.looked, "b next, a not again");
        acc.looked.clear();
        up.verify(acc, (d, t, n) -> { }, () -> true);
        assertEquals(List.of(remote("c.jpg")), acc.looked, "c, and the check is whole");
        acc.looked.clear();
        up.verify(acc, (d, t, n) -> { }, () -> true);
        assertEquals(List.of(remote("a.jpg"), remote("b.jpg"), remote("c.jpg")), acc.looked, "a whole check done: the next starts over");
    }

    @Test
    void anUploadThatArrivesDamagedIsTriedAgain() throws Exception {
        Account acc = new Account();
        Path c = chat("video.mp4");
        acc.damaging.add("video.mp4");
        MediaUpload up = uploader(c);
        assertEquals(0, up.run(acc, (d, t, n) -> { }, () -> true));
        assertEquals(1, up.retrying(), "the copy there didn't match: not counted, tried again");
        acc.damaging.clear();
        now = Present.of(up.nextRetry());
        assertEquals(1, up.run(acc, (d, t, n) -> { }, () -> true));
        assertArrayEquals(Files.readAllBytes(c.resolve("video.mp4")), acc.files.get(remote("video.mp4")));
    }

    @Test
    void checkingRepairsWhatDiffersTheBiggerCopyWinning() throws Exception {
        Account acc = new Account();
        Path c = chat("cortado-aqui.jpg", "cortado-alla.jpg", "distinto.jpg", "borrado.jpg");
        MediaUpload up = uploader(c);
        up.run(acc, (d, t, n) -> { }, () -> true);
        byte[] whole = Present.of(acc.files.get(remote("cortado-aqui.jpg"))).clone();

        // Here cut short (damaged here): it comes back from there, which is never touched.
        Files.writeString(c.resolve("cortado-aqui.jpg"), "conte");
        // There cut short: it goes up again.
        acc.files.put(remote("cortado-alla.jpg"), "conte".getBytes(StandardCharsets.UTF_8));
        // Same size, other content: neither is known to be right, neither is touched.
        byte[] other = Files.readAllBytes(c.resolve("distinto.jpg"));
        other[0] = 'X';
        acc.files.put(remote("distinto.jpg"), other);
        // Deleted here: stays there (an archive).
        Files.delete(c.resolve("borrado.jpg"));

        int repaired = up.verify(acc, (d, t, n) -> { }, () -> true);
        assertEquals(1, repaired, "one went back up");
        assertArrayEquals(whole, Files.readAllBytes(c.resolve("cortado-aqui.jpg")), "restored here");
        assertArrayEquals(whole, acc.files.get(remote("cortado-aqui.jpg")), "and there untouched");
        assertArrayEquals(Files.readAllBytes(c.resolve("cortado-alla.jpg")), acc.files.get(remote("cortado-alla.jpg")), "repaired there");
        assertArrayEquals(other, acc.files.get(remote("distinto.jpg")), "not touched there");
        assertEquals(1, up.conflicts(), "and the person is told");
        assertTrue(acc.files.containsKey(remote("borrado.jpg")), "never deleted there");
    }

    @Test
    void theRules() {
        Storage.RemoteFile sha = new Storage.RemoteFile(10, Checksums.SHA1, "abc");
        assertEquals(Checksums.Verdict.UPLOAD, Checksums.decide(10, "abc", null), "not there");
        assertEquals(Checksums.Verdict.SAME, Checksums.decide(10, "ABC", sha), "same checksum");
        assertEquals(Checksums.Verdict.UPLOAD, Checksums.decide(12, "def", sha), "bigger here");
        assertEquals(Checksums.Verdict.RESTORE_LOCAL, Checksums.decide(8, "def", sha), "bigger there");
        assertEquals(Checksums.Verdict.CONFLICT, Checksums.decide(10, "def", sha), "same size, other content");
        assertEquals(Checksums.Verdict.SAME, Checksums.decide(10, null, new Storage.RemoteFile(10, null, null)), "size only: same size");
        assertEquals(Checksums.Verdict.UPLOAD, Checksums.decide(12, null, new Storage.RemoteFile(10, null, null)));
    }

    @Test
    @SuppressFBWarnings(value = {"DMI_RANDOM_USED_ONLY_ONCE", "PREDICTABLE_RANDOM"}, justification = "seeded test data, the same on every run on purpose; nothing secret")
    void dropboxsContentHashIsTheHashOfEachBlocksHash(@TempDir Path tmp) throws Exception {
        byte[] data = new byte[5 * 1024 * 1024]; // two blocks: 4 MB and 1 MB
        new java.util.Random(3).nextBytes(data);
        Path f = Files.write(tmp.resolve("x.bin"), data);
        java.security.MessageDigest all = java.security.MessageDigest.getInstance("SHA-256");
        all.update(java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOfRange(data, 0, 4 * 1024 * 1024)));
        all.update(java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOfRange(data, 4 * 1024 * 1024, data.length)));
        assertEquals(java.util.HexFormat.of().formatHex(all.digest()), Checksums.of(f, Checksums.DROPBOX));
    }
}

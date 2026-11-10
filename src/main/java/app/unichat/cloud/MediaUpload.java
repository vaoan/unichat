package app.unichat.cloud;

import app.unichat.support.Errors;
import app.unichat.support.I18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What chats saved goes up to the account, each chat's folder as a folder of {@link CloudAccount#MEDIA}, until all of
 * it is there: a file that fails waits its turn again (1, 2, 4… up to 60 minutes, forever) without holding the others
 * back. Each upload is checked against the account's copy ({@link Checksums}); a check of everything uploaded repairs
 * what differs, the bigger copy winning, so a file damaged here never damages the one there ({@link Checksums#decide}).
 * Done files are remembered in {@code cloud-uploaded.txt}; nothing in the account is ever deleted.
 */
final class MediaUpload {
    static final Duration FIRST_WAIT = Duration.ofMinutes(1), LONGEST_WAIT = Duration.ofMinutes(60);

    /** A file that failed: how many times, when it's tried again, and why. */
    record Retry(int attempts, Instant next, String why) {}

    /** Told before each file is handled (which of how many, its name and size), and as its bytes go out. */
    @FunctionalInterface
    interface Progress {
        void uploading(int done, int total, String name);

        default void sent(long bytes, long size) { }
    }

    private final Path record;
    private final Supplier<List<Path>> folders;
    private final Supplier<Instant> clock;
    private final Set<String> done = new LinkedHashSet<>();
    private final Map<String, Retry> retries = new ConcurrentHashMap<>();
    private final Set<String> conflicts = ConcurrentHashMap.newKeySet();
    private boolean loaded;

    /** @param folders where chats save, each one's name its folder in the account */
    MediaUpload(Path record, Supplier<List<Path>> folders) {
        this(record, folders, Instant::now);
    }

    MediaUpload(Path record, Supplier<List<Path>> folders, Supplier<Instant> clock) {
        this.record = record;
        this.folders = folders;
        this.clock = clock;
    }

    /** Uploads what's missing and due; how many went up. A failure waits its turn; the rest go on. */
    int run(Storage dav, Progress progress) throws IOException {
        load();
        Map<String, Path> due = new LinkedHashMap<>();
        Instant now = clock.get();
        for (Path folder : folders.get()) {
            for (Path file : waiting(folder)) {
                Retry r = retries.get(key(folder, file));
                if (r == null || !now.isBefore(r.next())) due.put(key(folder, file), file);
            }
        }
        int sent = 0, index = 0;
        for (Map.Entry<String, Path> e : due.entrySet()) {
            progress.uploading(index++, due.size(), e.getValue().getFileName().toString());
            try {
                if (settle(dav, e.getKey(), e.getValue(), progress)) sent++;
                remember(e.getKey());
                retries.remove(e.getKey());
            } catch (IOException ex) {
                failed(e.getKey(), ex);
            }
        }
        return sent;
    }

    /**
     * Checks everything that went up whose file is still here, and repairs what differs; how many were repaired. A
     * file deleted here stays in the account (it's an archive).
     */
    int verify(Storage dav, Progress progress) throws IOException {
        load();
        Map<String, Path> here = new LinkedHashMap<>();
        for (Path folder : folders.get()) {
            for (String entry : done) {
                if (!entry.startsWith(folder.getFileName() + "/")) continue;
                Path file = folder.resolve(entry.substring(entry.indexOf('/') + 1));
                if (Files.isRegularFile(file)) here.put(entry, file);
            }
        }
        int repaired = 0, index = 0;
        for (Map.Entry<String, Path> e : here.entrySet()) {
            progress.uploading(index++, here.size(), e.getValue().getFileName().toString());
            try {
                if (settle(dav, e.getKey(), e.getValue(), progress)) repaired++;
            } catch (IOException ex) {
                failed(e.getKey(), ex);
            }
        }
        return repaired;
    }

    /** One file against its copy there: true if it went up (or back up). */
    private boolean settle(Storage dav, String key, Path file, Progress progress) throws IOException {
        String dir = CloudAccount.MEDIA + "/" + key.substring(0, key.indexOf('/'));
        String remote = CloudAccount.MEDIA + "/" + key;
        Storage.RemoteFile there = dav.info(remote);
        switch (Checksums.decide(Files.size(file), there == null ? null : localHash(file, there), there)) {
            case SAME -> {
                conflicts.remove(key);
                return false;
            }
            case UPLOAD -> {
                dav.mkdirs(dir);
                long size = Files.size(file);
                dav.put(remote, file, bytes -> progress.sent(bytes, size));
                Storage.RemoteFile after = dav.info(remote);
                if (Checksums.decide(Files.size(file), after == null ? null : localHash(file, after), after) != Checksums.Verdict.SAME) {
                    throw new IOException(I18n.t("cloud.error.mismatch"));
                }
                conflicts.remove(key);
                return true;
            }
            case RESTORE_LOCAL -> {
                restore(dav, remote, file);
                conflicts.remove(key);
                return false;
            }
            default -> {
                conflicts.add(key);
                return false;
            }
        }
    }

    private static String localHash(Path file, Storage.RemoteFile there) throws IOException {
        return there.kind() == null ? null : Checksums.of(file, there.kind());
    }

    /** The account's copy in place of this one (this one was cut short), written whole before it replaces it. */
    private static void restore(Storage dav, String remote, Path file) throws IOException {
        byte[] data = dav.get(remote);
        if (data == null) throw new IOException(I18n.t("cloud.error.mismatch"));
        Path part = file.resolveSibling("." + file.getFileName() + ".restore");
        Files.write(part, data);
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void failed(String key, IOException e) {
        int attempts = retries.containsKey(key) ? retries.get(key).attempts() + 1 : 1;
        long minutes = Math.min(LONGEST_WAIT.toMinutes(), FIRST_WAIT.toMinutes() << Math.min(attempts - 1, 6));
        retries.put(key, new Retry(attempts, clock.get().plus(Duration.ofMinutes(minutes)), Errors.message(e)));
    }

    /** How many saved files haven't gone up yet. */
    int waiting() throws IOException {
        load();
        int n = 0;
        for (Path folder : folders.get()) n += waiting(folder).size();
        return n;
    }

    /** Files waiting for another try, and the soonest one (null when none). */
    int retrying() { return retries.size(); }

    Instant nextRetry() {
        return retries.values().stream().map(Retry::next).min(Instant::compareTo).orElse(null);
    }

    /** Files whose copy there has the same size and other content: neither is touched; the person is told. */
    int conflicts() { return conflicts.size(); }

    private static String key(Path folder, Path file) {
        return folder.getFileName() + "/" + file.getFileName();
    }

    private List<Path> waiting(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        List<Path> out = new java.util.ArrayList<>();
        try (DirectoryStream<Path> in = Files.newDirectoryStream(folder)) {
            for (Path p : in) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p) || name.startsWith(".") || name.endsWith(".part") || name.endsWith(".ytdl")) continue;
                if (!done.contains(key(folder, p))) out.add(p);
            }
        }
        out.sort(null);
        return out;
    }

    private void load() throws IOException {
        if (loaded) return;
        if (Files.exists(record)) done.addAll(Files.readAllLines(record, StandardCharsets.UTF_8));
        loaded = true;
    }

    private void remember(String entry) throws IOException {
        if (!done.add(entry)) return;
        Files.createDirectories(record.getParent());
        Files.writeString(record, entry + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}

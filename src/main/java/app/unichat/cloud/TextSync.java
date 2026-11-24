package app.unichat.cloud;

import app.unichat.support.PathParts;
import app.unichat.support.SafeFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What chats said, kept the same on every computer of the person's: each computer writes only its own files (its
 * folder "here"), which go up under its machine's name ({@link #ROOT}{@code /<machine>/}), with an {@code index.json}
 * of their sizes; the others' come down into a folder each, beside "here". No file has two writers, so nothing is ever
 * replaced by another computer's copy: the files only grow, and a bigger one is the newer. Merging what they say is
 * the reader's ({@code autosave.TextLog}).
 */
final class TextSync {
    static final String ROOT = CloudAccount.ROOT + "/Texts";
    private static final String HERE = "here";
    private static final String INDEX = TextIndex.NAME;
    /** Names of ours only (machines are {@link Ids}): anything else in the account never becomes a path here. */
    private static final Pattern FILE = TextIndex.FILE;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path local;
    private final Path record;
    private final String machine;
    /** What went up of each own file (its size), and each other machine's index as last read (its stamp). */
    private final Map<String, Long> sent = new HashMap<>();
    /** Where {@link #sent} went: a record from before the vault knew only the plain account. */
    private String sentTo = PLAIN;
    private final Map<String, String> stamps = new HashMap<>();
    /** A file went up and the index saying so not yet: until it does, the others never look for what's new. */
    private boolean indexDue;
    private boolean loaded;
    /** The other machines whose texts in the vault didn't open at the last run: left as they are, never written here. */
    private final Set<String> damaged = new TreeSet<>();

    /** @param local where the texts are kept, "here" and one folder per other machine */
    TextSync(Path local, Path record, String machine) {
        this.local = local;
        this.record = record;
        this.machine = machine;
    }

    /** The account itself, as {@link #run}'s {@code to} says where the texts go: plain, or a vault ("vault:" and its id). */
    static final String PLAIN = "plain";

    /** {@link #run(Storage, String)} to the plain account. */
    synchronized Set<String> run(Storage storage) throws IOException {
        return run(storage, PLAIN);
    }

    /**
     * Sends what grew here, then brings what grew on the other computers; the chats (their keys) that something came
     * for. A failure stops the round where it was: what was done is remembered, the rest is tried at the next.
     * {@code to} names where {@code storage} keeps them (plain, or which vault): what was sent is of one of them, so when
     * it's another this computer's own files all go again (it's the only one to write them); a launch to the same one
     * sends nothing again.
     */
    synchronized Set<String> run(Storage storage, String to) throws IOException {
        load();
        if (!to.equals(sentTo)) {
            sent.clear();
            indexDue = false;
            sentTo = to;
        }
        try {
            send(storage);
            return bring(storage);
        } finally {
            save();
        }
    }

    /**
     * The others' indexes may be other files now (the vault opened or closed here): each is read again; only what grew
     * comes. What this computer sent goes again only if it goes somewhere else ({@link #run(Storage, String)}).
     */
    synchronized void unseen() throws IOException {
        load();
        stamps.clear();
        save();
    }

    private void send(Storage storage) throws IOException {
        Path own = local.resolve(HERE);
        if (!Files.isDirectory(own)) return;
        Map<String, Path> files = new java.util.TreeMap<>();
        try (Stream<Path> s = Files.list(own)) {
            s.filter(p -> FILE.matcher(PathParts.name(p)).matches()).forEach(p -> files.put(PathParts.name(p), p));
        }
        String folder = ROOT + "/" + machine;
        boolean made = false;
        for (Map.Entry<String, Path> f : files.entrySet()) {
            // A month that ended never changes: told by its size, it isn't read again each round.
            if (sent.getOrDefault(f.getKey(), -1L) == Files.size(f.getValue())) continue;
            byte[] data = Files.readAllBytes(f.getValue()); // one read: what goes up and the size told are the same
            if (!made) storage.mkdirs(folder);
            made = true;
            storage.put(folder + "/" + f.getKey(), data);
            sent.put(f.getKey(), (long) data.length);
            indexDue = true;
        }
        if (!indexDue) return;
        if (!made) storage.mkdirs(folder);
        storage.put(folder + "/" + INDEX, TextIndex.write(sent));
        indexDue = false;
    }

    /**
     * Whether {@code plain} at {@code path} is a text this computer sent: one of its own, whose content is the start of
     * the file here (texts only grow). Its index never is ({@link #indexAgain}). One of its own names that isn't what it
     * has here (someone else's in its folder) is sent again from here, so it doesn't stay in the way.
     */
    synchronized boolean sentHere(String path, byte[] plain) throws IOException {
        String folder = ROOT + "/" + machine + "/";
        if (!path.startsWith(folder)) return false;
        String name = path.substring(folder.length());
        if (!FILE.matcher(name).matches()) return false;
        Path here = local.resolve(HERE).resolve(name);
        if (!Files.exists(here)) return false;
        byte[] mine = Files.readAllBytes(here);
        if (mine.length >= plain.length && java.util.Arrays.equals(mine, 0, plain.length, plain, 0, plain.length)) return true;
        load();
        sent.remove(name);
        save();
        return false;
    }

    /**
     * What was sent is now at {@code to} too (the vault turned off here copied it there as it was): nothing goes again
     * for the change of place.
     */
    synchronized void sentAt(String to) throws IOException {
        load();
        sentTo = to;
        save();
    }

    /** The index goes up again at the next run, so it says what this computer has (after the vault's key changed). */
    synchronized void indexAgain() throws IOException {
        load();
        indexDue = true;
        save();
    }

    /** The other machines whose texts in the vault didn't open at the last run. */
    synchronized Set<String> damaged() {
        return Set.copyOf(damaged);
    }

    private Set<String> bring(Storage storage) throws IOException {
        Set<String> came = new TreeSet<>();
        damaged.clear();
        for (String other : storage.list(ROOT)) {
            if (other.equals(machine) || !Ids.ours(other)) continue;
            try {
                bring(storage, other, came);
            } catch (SealedStorage.Damaged e) {
                damaged.add(other); // read again next round; the others still come
            }
        }
        return came;
    }

    /** One other machine's texts, when its index changed. */
    private void bring(Storage storage, String other, Set<String> came) throws IOException {
        String folder = ROOT + "/" + other;
        String stamp = storage.stamp(folder + "/" + INDEX);
        if (stamp == null || stamp.equals(stamps.get(other))) return;
        byte[] index = storage.get(folder + "/" + INDEX);
        if (index == null) return;
        Path into = local.resolve(other);
        for (Map.Entry<String, Long> f : TextIndex.read(index).entrySet()) {
            java.util.regex.Matcher name = FILE.matcher(f.getKey());
            if (!name.matches()) continue;
            Path here = into.resolve(f.getKey());
            if (Files.exists(here) && Files.size(here) >= f.getValue()) continue;
            app.unichat.support.Folders.ownerOnly(local); // what chats said: no other account of the computer reads it
            Files.createDirectories(into);
            try {
                if (storage.get(folder + "/" + f.getKey(), here)) came.add(name.group(1));
            } catch (SealedStorage.Damaged e) {
                damaged.add(other); // its later files still come; this one is looked for again next round
            }
        }
        if (!damaged.contains(other)) stamps.put(other, stamp); // only once all of it came: what failed is read again next time
    }

    private void load() throws IOException {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(record)) return;
        JsonNode n = JSON.readTree(record.toFile());
        n.path("sent").properties().forEach(e -> sent.put(e.getKey(), e.getValue().asLong()));
        n.path("stamps").properties().forEach(e -> stamps.put(e.getKey(), e.getValue().asText()));
        indexDue = n.path("indexDue").asBoolean(false);
        sentTo = n.path("sentTo").asText(PLAIN);
    }

    private void save() throws IOException {
        ObjectNode n = JSON.createObjectNode();
        sent.forEach(n.putObject("sent")::put);
        stamps.forEach(n.putObject("stamps")::put);
        n.put("indexDue", indexDue);
        n.put("sentTo", sentTo);
        SafeFile.at(record).write(JSON.writeValueAsBytes(n));
    }
}

package app.unichat.cloud;

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
    static final String ROOT = CloudAccount.ROOT + "/Textos";
    private static final String HERE = "here", INDEX = TextIndex.NAME;
    /** Names of ours only (machines are {@link Ids}): anything else in the account never becomes a path here. */
    private static final Pattern FILE = TextIndex.FILE;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path local;
    private final Path record;
    private final String machine;
    /** What went up of each own file (its size), and each other machine's index as last read (its stamp). */
    private final Map<String, Long> sent = new HashMap<>();
    private final Map<String, String> stamps = new HashMap<>();
    /** A file went up and the index saying so not yet: until it does, the others never look for what's new. */
    private boolean indexDue;
    private boolean loaded;

    /** @param local where the texts are kept, "here" and one folder per other machine */
    TextSync(Path local, Path record, String machine) {
        this.local = local;
        this.record = record;
        this.machine = machine;
    }

    /**
     * Sends what grew here, then brings what grew on the other computers; the chats (their keys) that something came
     * for. A failure stops the round where it was: what was done is remembered, the rest is tried at the next.
     */
    synchronized Set<String> run(Storage storage) throws IOException {
        load();
        try {
            send(storage);
            return bring(storage);
        } finally {
            save();
        }
    }

    /**
     * The account is another storage now (the vault opened or closed here): each index of the others' is read again (only
     * what grew comes), and this computer's own files all go again, since what went up went to the other one and this
     * computer is the only one to write them.
     */
    synchronized void unseen() throws IOException {
        load();
        stamps.clear();
        sent.clear();
        save();
    }

    private void send(Storage storage) throws IOException {
        Path own = local.resolve(HERE);
        if (!Files.isDirectory(own)) return;
        Map<String, Path> files = new java.util.TreeMap<>();
        try (Stream<Path> s = Files.list(own)) {
            s.filter(p -> FILE.matcher(p.getFileName().toString()).matches()).forEach(p -> files.put(p.getFileName().toString(), p));
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

    private Set<String> bring(Storage storage) throws IOException {
        Set<String> came = new TreeSet<>();
        for (String other : storage.list(ROOT)) {
            if (other.equals(machine) || !Ids.ours(other)) continue;
            String folder = ROOT + "/" + other;
            String stamp = storage.stamp(folder + "/" + INDEX);
            if (stamp == null || stamp.equals(stamps.get(other))) continue;
            byte[] index = storage.get(folder + "/" + INDEX);
            if (index == null) continue;
            Path into = local.resolve(other);
            for (Map.Entry<String, Long> f : TextIndex.read(index).entrySet()) {
                java.util.regex.Matcher name = FILE.matcher(f.getKey());
                if (!name.matches()) continue;
                Path here = into.resolve(f.getKey());
                if (Files.exists(here) && Files.size(here) >= f.getValue()) continue;
                app.unichat.support.Folders.ownerOnly(local); // what chats said: no other account of the computer reads it
                Files.createDirectories(into);
                if (storage.get(folder + "/" + f.getKey(), here)) came.add(name.group(1));
            }
            stamps.put(other, stamp); // only once all of it came: a failure above reads the index again next time
        }
        return came;
    }

    private void load() throws IOException {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(record)) return;
        JsonNode n = JSON.readTree(record.toFile());
        n.path("sent").fields().forEachRemaining(e -> sent.put(e.getKey(), e.getValue().asLong()));
        n.path("stamps").fields().forEachRemaining(e -> stamps.put(e.getKey(), e.getValue().asText()));
        indexDue = n.path("indexDue").asBoolean(false);
    }

    private void save() throws IOException {
        ObjectNode n = JSON.createObjectNode();
        sent.forEach(n.putObject("sent")::put);
        stamps.forEach(n.putObject("stamps")::put);
        n.put("indexDue", indexDue);
        SafeFile.at(record).write(JSON.writeValueAsBytes(n));
    }
}

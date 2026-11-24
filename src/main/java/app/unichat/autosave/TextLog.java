package app.unichat.autosave;

import app.unichat.support.Hashes;
import app.unichat.support.PathParts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * What chats said, kept to read what was deleted later. Each computer appends only to its own files ({@link #HERE}:
 * one per chat and month, a JSON line per version of a message), never anyone else's, so the cloud never sees two
 * writers of one file; the files of the person's other computers sit beside them, one folder each (the cloud brings
 * them). Reading merges them all: each message's distinct texts, oldest seen first. Only the texts thread calls it.
 */
final class TextLog {
    /** This computer's own folder; the others are named after their machine. */
    static final String HERE = "here";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Path dir;
    private final Supplier<Instant> clock;
    /** By chat key, what this computer wrote already ({@link #version}): read from its files once. */
    private final Map<String, Set<String>> written = new HashMap<>();

    TextLog(Path dir, Supplier<Instant> clock) {
        this.dir = dir;
        this.clock = clock;
    }

    /**
     * A chat's name in the files: the same on every computer (one Matrix account), and nothing of who it is in the
     * names of the account's files (what they hold says it). Twelve hex digits leave a clash between two of a person's
     * chats out of reach.
     */
    static String key(String chatId) {
        return Hashes.sha256(chatId).substring(0, 12);
    }

    /** Appends what this computer hasn't written of {@code seen} (the chat's texts now); whether it wrote any. */
    boolean record(String chatId, List<SavedText> seen) throws IOException {
        String key = key(chatId);
        Set<String> known = known(key);
        StringBuilder lines = new StringBuilder();
        List<String> added = new ArrayList<>();
        for (SavedText t : seen) {
            String v = version(t);
            if (known.contains(v) || added.contains(v)) continue;
            lines.append(t.json()).append('\n');
            added.add(v);
        }
        if (added.isEmpty()) return false;
        app.unichat.support.Folders.ownerOnly(dir); // a copy of what chats said: no other account of the computer reads it
        Path own = Files.createDirectories(dir.resolve(HERE));
        Path file = own.resolve(key + " " + MONTH.format(clock.get()) + ".jsonl");
        // A write cut short (a full disk) left a line without its end: glued to it, the next entry was lost too.
        if (!endsALine(file)) lines.insert(0, '\n');
        Files.writeString(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        known.addAll(added); // only once on disk: a failed write is tried again with the next change
        return true;
    }

    /** Whether {@code file} is empty, missing, or ends with a line's end. */
    private static boolean endsALine(Path file) throws IOException {
        if (!Files.exists(file) || Files.size(file) == 0) return true;
        try (java.nio.channels.SeekableByteChannel c = Files.newByteChannel(file)) {
            java.nio.ByteBuffer last = java.nio.ByteBuffer.allocate(1);
            c.position(c.size() - 1).read(last);
            return last.get(0) == '\n';
        }
    }

    /** Every version of {@code messageId} kept by any computer, oldest seen first; empty: there's no copy. */
    List<SavedText> versions(String chatId, String messageId) throws IOException {
        return merged(chatId).stream().filter(t -> t.id().equals(messageId)).toList();
    }

    /** Everything kept of the chat by every computer: by when it was sent, each message's versions oldest seen first. */
    List<SavedText> merged(String chatId) throws IOException {
        Map<String, SavedText> first = new LinkedHashMap<>();
        for (SavedText t : read(key(chatId), false)) first.merge(version(t), t, (a, b) -> a.seen().isAfter(b.seen()) ? b : a);
        return first.values().stream().sorted(Comparator.comparing(SavedText::at).thenComparing(SavedText::id)
                .thenComparing(SavedText::seen)).toList();
    }

    /** What this computer wrote of the chat with {@code key}. */
    private Set<String> known(String key) throws IOException {
        Set<String> k = written.get(key);
        if (k != null) return k;
        k = new HashSet<>();
        for (SavedText t : read(key, true)) k.add(version(t));
        written.put(key, k);
        return k;
    }

    /** One version: the message and what it said (a hash of it, so a long chat's texts aren't all kept in memory). */
    private static String version(SavedText t) {
        return t.id() + "\n" + Hashes.sha256(t.text());
    }

    /** The entries of {@code key}'s files, this computer's only or every computer's; lines that aren't whole left out. */
    private List<SavedText> read(String key, boolean ownOnly) throws IOException {
        List<SavedText> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        List<Path> folders;
        try (Stream<Path> s = Files.list(dir)) {
            folders = s.filter(Files::isDirectory).filter(f -> !ownOnly || PathParts.name(f).equals(HERE)).toList();
        }
        for (Path folder : folders) {
            List<Path> files;
            try (Stream<Path> s = Files.list(folder)) {
                files = s.filter(f -> PathParts.name(f).startsWith(key + " ") && PathParts.name(f).endsWith(".jsonl")).toList();
            }
            for (Path f : files) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    SavedText t = SavedText.parse(line);
                    if (t != null) out.add(t);
                }
            }
        }
        return out;
    }
}

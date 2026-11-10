package app.unichat.service;

import app.unichat.settings.SyncedFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-chat preferences and folders, kept in Unichat's own {@code preferences.json} (it travels with the person) and
 * reconciled with what the server says. A field the person never touched follows the server in both directions; once
 * they choose, the file wins. {@code server-seen.json} (this machine only) remembers which flags this server has said
 * "true" about, so a fresh server's empty answers don't erase what was copied from the old one.
 * Disk errors are logged, never thrown: memory stays right and the old file stays whole. Each save keeps the version it
 * replaces as {@code preferences.json.bak}, the way back if the file turns up damaged.
 * Changes never wait for the disk: callers change preferences on the UI thread or holding the sync lock, so what changed
 * is copied under this lock and written on a daemon thread, one write for however many changes came meanwhile.
 * {@link #flush} waits for it: whoever ends the app (or a session) must call it, a daemon thread doesn't outlive the JVM.
 */
public final class PreferencesFile {
    public enum Flag {
        ARCHIVED("archived"), MUTED("muted"), FAVORITE("favorite"), LOW_PRIORITY("lowPriority"), PINNED("pinned"),
        /** Save what others send in this chat ({@code autosave}): Unichat's own, no server ever says it. */
        AUTO_SAVE("autoSave"),
        /** A kind left out of what's saved: all are saved unless left out (what was on before kinds existed stays all). */
        AUTO_SAVE_NO_PHOTOS("autoSaveNoPhotos"), AUTO_SAVE_NO_VIDEOS("autoSaveNoVideos"), AUTO_SAVE_NO_AUDIO("autoSaveNoAudio"),
        AUTO_SAVE_NO_DOCUMENTS("autoSaveNoDocuments"), AUTO_SAVE_NO_GIFS("autoSaveNoGifs"), AUTO_SAVE_NO_LINKS("autoSaveNoLinks"),
        /** The profile doesn't show what was shared in this chat (its options). */
        MEDIA_HIDDEN("mediaHidden");

        final String json;

        Flag(String json) { this.json = json; }
    }

    /** What the server says; null = nothing heard about it yet. */
    public record ServerValues(Boolean archived, Boolean muted, Boolean favorite, Boolean lowPriority, Boolean pinned,
                               Instant reminder, boolean reminderKnown) {
        Boolean of(Flag f) {
            return switch (f) {
                case ARCHIVED -> archived;
                case MUTED -> muted;
                case FAVORITE -> favorite;
                case LOW_PRIORITY -> lowPriority;
                case PINNED -> pinned;
                default -> null; // Unichat's own: no server says them
            };
        }
    }

    public record Effective(boolean archived, boolean muted, boolean favorite, boolean lowPriority, boolean pinned,
                            Instant reminder, boolean muteChosenHere) {}

    private static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One chat's entry; a flag missing from {@code values} was never set, a reminder is "touched" once {@code reminderSet}. */
    private static final class Entry {
        final Map<Flag, Boolean> values = new EnumMap<>(Flag.class);
        final Set<Flag> fromServer = EnumSet.noneOf(Flag.class);
        boolean reminderSet;
        Instant reminder;
        /** Fields written by a newer Unichat: kept as they came. */
        final ObjectNode extra = JSON.createObjectNode();

        boolean touched(Flag f) { return values.containsKey(f) && !fromServer.contains(f); }
    }

    private static final class MutableFolder {
        final String id;
        String name;
        final Set<String> keys = new LinkedHashSet<>();
        final ObjectNode extra = JSON.createObjectNode();
        boolean favorite;

        MutableFolder(String id, String name) { this.id = id; this.name = name; }
    }

    private final Path file, seenFile;
    private final Map<String, Entry> chats = new LinkedHashMap<>();
    private final List<MutableFolder> folders = new ArrayList<>();
    private final Map<String, Set<Flag>> seen = new HashMap<>();
    private final ObjectNode extraRoot = JSON.createObjectNode();
    private String setAside, server;
    private boolean dirty, seenDirty, unreadable, recovered, saveFailing;
    /** The main file is still the damaged one, and memory holds what came from the backup: it must not become the backup. */
    private boolean damagedInPlace;
    private Runnable onSaveFailure;
    private int announcements;
    private int batching;
    private boolean writePending, writerRunning;
    /** Times the main file was replaced since this object was created. */
    private int mainWrites;

    public PreferencesFile(Path preferences, Path serverSeen) {
        this.file = preferences;
        this.seenFile = serverSeen;
        if (file != null) load();
        if (seenFile != null) loadSeen();
    }

    public static PreferencesFile inMemory() { return new PreferencesFile(null, null); }

    /** The name the unreadable file was set aside under, or null. */
    public String setAside() { return setAside; }

    /**
     * True while an unreadable file could not be moved aside: nothing is written to it until the move works (every
     * change retries), so the original is never overwritten. The caller should warn the person.
     */
    public synchronized boolean unreadable() { return unreadable; }

    /** True if the file was damaged and these preferences are the backup's: the last change may be missing. */
    public synchronized boolean recoveredFromBackup() { return recovered; }

    /**
     * Called when saves start failing, again only after one worked in between; right away if they already are. On a
     * thread of its own: callers change preferences holding their own locks, and this lock is a leaf.
     */
    public synchronized void onSaveFailure(Runnable listener) {
        onSaveFailure = listener;
        if (saveFailing && listener != null) announce(listener);
    }

    private void announce(Runnable listener) {
        announcements++;
        Thread.ofPlatform().daemon().name("preferences-save-failed").start(listener);
    }

    /**
     * Failures announced so far. Decided under the lock (in {@link #flush} or {@link #onSaveFailure}); only the telling
     * goes on another thread: a test reads here whether a warning went out, without waiting a while to see.
     */
    synchronized int announcements() { return announcements; }

    public synchronized Effective reconcile(String key, ServerValues server) {
        for (Flag f : Flag.values()) {
            Boolean said = server.of(f);
            if (said == null) continue;
            if (said && seen.computeIfAbsent(key, k -> EnumSet.noneOf(Flag.class)).add(f)) seenDirty = true;
            Entry e = chats.get(key);
            if (e != null && e.touched(f)) continue;
            if (said) {
                Entry target = entry(key);
                if (!Boolean.TRUE.equals(target.values.put(f, true)) | target.fromServer.add(f)) dirty = true;
            } else if (seen.getOrDefault(key, Set.of()).contains(f)) {
                Entry target = entry(key);
                target.values.put(f, false);
                target.fromServer.add(f);
                forgetSeen(key, f);
                dirty = true;
            }
        }
        Entry e = chats.get(key);
        if (server.reminderKnown() && server.reminder() != null && (e == null || !e.reminderSet)) {
            Entry target = entry(key);
            target.reminderSet = true;
            target.reminder = server.reminder();
            dirty = true;
        }
        return current(key);
    }

    public synchronized Effective current(String key) {
        Entry e = chats.get(key);
        if (e == null) return new Effective(false, false, false, false, false, null, false);
        return new Effective(is(e, Flag.ARCHIVED), is(e, Flag.MUTED), is(e, Flag.FAVORITE), is(e, Flag.LOW_PRIORITY),
                is(e, Flag.PINNED), e.reminder, e.touched(Flag.MUTED));
    }

    /**
     * What to show before the server answers: the file where it has the field, else what a saved sync said. Reads only:
     * old news can't change the file or what this server is remembered to have said, so a stale false erases nothing.
     */
    public synchronized Effective preview(String key, ServerValues cached) {
        Entry e = chats.get(key);
        return new Effective(shown(e, Flag.ARCHIVED, cached), shown(e, Flag.MUTED, cached), shown(e, Flag.FAVORITE, cached),
                shown(e, Flag.LOW_PRIORITY, cached), shown(e, Flag.PINNED, cached),
                e != null && e.reminderSet ? e.reminder : cached.reminder(), e != null && e.touched(Flag.MUTED));
    }

    private static boolean shown(Entry e, Flag f, ServerValues cached) {
        return e != null && e.values.containsKey(f) ? is(e, f) : Boolean.TRUE.equals(cached.of(f));
    }

    public synchronized void choose(String key, Flag flag, boolean value) {
        Entry e = entry(key);
        e.values.put(flag, value);
        e.fromServer.remove(flag);
        changed();
    }

    /** Set by a rule, not the person (a mute from the phone archives): the person hasn't touched it. */
    public synchronized void follow(String key, Flag flag, boolean value) {
        Entry e = entry(key);
        if (e.touched(flag)) return;
        e.fromServer.add(flag);
        e.values.put(flag, value);
        changed();
    }

    public synchronized void chooseReminder(String key, Instant at) {
        Entry e = entry(key);
        e.reminderSet = true;
        e.reminder = at;
        changed();
    }

    public synchronized void rekey(String oldKey, String newKey) {
        if (oldKey.equals(newKey)) return;
        Entry old = chats.remove(oldKey);
        if (old != null) {
            Entry target = chats.get(newKey);
            if (target == null) {
                chats.put(newKey, old);
            } else {
                for (var v : old.values.entrySet()) {
                    if (target.touched(v.getKey())) continue;
                    target.values.put(v.getKey(), v.getValue());
                    if (old.fromServer.contains(v.getKey())) target.fromServer.add(v.getKey());
                    else target.fromServer.remove(v.getKey());
                }
                if (old.reminderSet && (!target.reminderSet || old.reminder == null)) {
                    target.reminderSet = true;
                    target.reminder = old.reminder;
                }
            }
        }
        Set<Flag> oldSeen = seen.remove(oldKey);
        if (oldSeen != null) {
            seen.computeIfAbsent(newKey, k -> EnumSet.noneOf(Flag.class)).addAll(oldSeen);
            seenDirty = true;
        }
        for (MutableFolder f : folders) {
            if (!f.keys.remove(oldKey)) continue;
            f.keys.add(newKey);
        }
        changed();
    }

    /**
     * Says which homeserver the answers come from. What a server said "true" about means nothing for another one (a
     * rebuilt server, a different homeserver), so a change forgets it and that server's "false" can't erase anything.
     */
    public synchronized void useServer(String homeserver) {
        if (java.util.Objects.equals(homeserver, server)) return;
        server = homeserver;
        seen.clear();
        seenDirty = true;
        saveLater();
    }

    /** Muted = archived ({@link QuietRule}): the person's choice covers both. */
    public synchronized void chooseQuiet(String key, boolean on) {
        batch(() -> {
            choose(key, Flag.ARCHIVED, on);
            choose(key, Flag.MUTED, on);
        });
    }

    /** Several changes, written once at the end: a server's hundreds of channels shouldn't rewrite the file each. */
    public synchronized void batch(Runnable changes) {
        batching++;
        try {
            changes.run();
        } finally {
            if (--batching == 0 && (dirty || seenDirty)) saveLater();
        }
    }

    /**
     * Returns once everything changed so far has been written, or has failed to be (reported as ever): for the end of the
     * app and of a session. Blocks, so never on the UI thread during normal use. The writer takes this lock: don't call it
     * from inside {@link #batch}, and don't hold a lock that a batch's changes may need.
     */
    public synchronized void flush() {
        if (dirty || seenDirty) saveLater();
        boolean interrupted = false;
        while (writerRunning) {
            try {
                wait();
            } catch (InterruptedException e) {
                interrupted = true; // the last change is worth more than a prompt answer
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    synchronized int mainWrites() { return mainWrites; }

    public synchronized List<Folder> folders() {
        return folders.stream().map(f -> new Folder(f.id, f.name, List.copyOf(f.keys), f.favorite)).toList();
    }

    public synchronized String createFolder(String name) {
        String clean = cleanName(name);
        String id;
        do { id = UUID.randomUUID().toString().substring(0, 8); } while (folder(id) != null);
        folders.add(new MutableFolder(id, clean));
        changed();
        return id;
    }

    public synchronized void renameFolder(String id, String name) {
        String clean = cleanName(name);
        MutableFolder f = folder(id);
        if (f == null) return;
        f.name = clean;
        changed();
    }

    /** Among the favorites, or not. */
    public synchronized void setFolderFavorite(String id, boolean favorite) {
        MutableFolder f = folder(id);
        if (f == null || f.favorite == favorite) return;
        f.favorite = favorite;
        changed();
    }

    public synchronized void deleteFolder(String id) {
        if (folders.removeIf(f -> f.id.equals(id))) changed();
    }

    public synchronized void setInFolder(String folderId, String key, boolean in) {
        MutableFolder f = folder(folderId);
        if (f == null) return;
        if (in ? f.keys.add(key) : f.keys.remove(key)) changed();
    }

    // ---- internals

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A folder needs a name");
        return name.strip();
    }

    private static boolean is(Entry e, Flag f) { return Boolean.TRUE.equals(e.values.get(f)); }

    /** Whether {@code flag} is on for the chat with this key, as the file has it (for those no server says). */
    public synchronized boolean isOn(String key, Flag flag) {
        Entry e = chats.get(key);
        return e != null && is(e, flag);
    }

    /** What the chat with this key saves of what it receives; empty when it saves nothing. */
    public synchronized java.util.Set<app.unichat.model.SaveKind> saveKinds(String key) {
        Entry e = chats.get(key);
        java.util.Set<app.unichat.model.SaveKind> out = EnumSet.noneOf(app.unichat.model.SaveKind.class);
        if (e == null || !is(e, Flag.AUTO_SAVE)) return out;
        for (app.unichat.model.SaveKind k : app.unichat.model.SaveKind.values()) if (!is(e, leftOut(k))) out.add(k);
        return out;
    }

    /** The person chose what this chat saves; nothing turns it off. */
    public synchronized void chooseSaveKinds(String key, java.util.Set<app.unichat.model.SaveKind> kinds) {
        Entry e = entry(key);
        e.values.put(Flag.AUTO_SAVE, !kinds.isEmpty());
        for (app.unichat.model.SaveKind k : app.unichat.model.SaveKind.values()) e.values.put(leftOut(k), !kinds.contains(k));
        changed();
    }

    private static Flag leftOut(app.unichat.model.SaveKind k) {
        return switch (k) {
            case PHOTOS -> Flag.AUTO_SAVE_NO_PHOTOS;
            case VIDEOS -> Flag.AUTO_SAVE_NO_VIDEOS;
            case AUDIO -> Flag.AUTO_SAVE_NO_AUDIO;
            case DOCUMENTS -> Flag.AUTO_SAVE_NO_DOCUMENTS;
            case GIFS -> Flag.AUTO_SAVE_NO_GIFS;
            case LINKS -> Flag.AUTO_SAVE_NO_LINKS;
        };
    }

    private Entry entry(String key) { return chats.computeIfAbsent(key, k -> new Entry()); }

    private MutableFolder folder(String id) {
        return folders.stream().filter(f -> f.id.equals(id)).findFirst().orElse(null);
    }

    private void forgetSeen(String key, Flag f) {
        Set<Flag> flags = seen.get(key);
        if (flags != null && flags.remove(f)) {
            seenDirty = true;
            if (flags.isEmpty()) seen.remove(key);
        }
    }

    private void changed() {
        dirty = true;
        if (batching == 0) saveLater();
    }

    private void load() {
        if (!Files.exists(file)) return;
        if (readFrom(file)) return;
        Path backup = backupOf(file);
        recovered = Files.exists(backup) && readFrom(backup);
        unreadable = true;
        setAsideBadFile();
        if (!recovered) return;
        dirty = true; // what came back isn't in the main file yet
        if (unreadable) return; // the warning says so; nothing touches the file until its copy exists
        writeMain();
        saveFailing = dirty;
    }

    /** Fills memory from {@code from}; on false it stays empty, whatever was read before the damage. */
    private boolean readFrom(Path from) {
        try {
            JsonNode root = JSON.readTree(from.toFile());
            if (root == null || !root.isObject() || root.path("version").asInt() != VERSION) {
                throw new IOException("unknown format");
            }
            readRoot(root);
            return true;
        } catch (IOException | RuntimeException e) {
            chats.clear();
            folders.clear();
            extraRoot.removeAll();
            return false;
        }
    }

    private static Path backupOf(Path target) { return target.resolveSibling(target.getFileName() + ".bak"); }

    private void readRoot(JsonNode root) {
        if (root.has("chats") && !root.get("chats").isObject()) throw new IllegalArgumentException("chats");
        if (root.has("folders") && !root.get("folders").isArray()) throw new IllegalArgumentException("folders");
        copyExtra(root, extraRoot, "version", "chats", "folders");
        for (Iterator<Map.Entry<String, JsonNode>> it = root.path("chats").fields(); it.hasNext(); ) {
            var c = it.next();
            if (!c.getValue().isObject()) throw new IllegalArgumentException("chat entry");
            chats.put(c.getKey(), readEntry(c.getValue()));
        }
        for (JsonNode n : root.path("folders")) {
            boolean shaped = n.isObject() && n.path("id").isTextual() && !n.path("id").asText().isBlank()
                    && (!n.has("chats") || n.get("chats").isArray());
            if (!shaped) throw new IllegalArgumentException("folder");
            MutableFolder f = new MutableFolder(n.path("id").asText(), n.path("name").asText());
            for (JsonNode k : n.path("chats")) f.keys.add(k.asText());
            f.favorite = n.path("favorite").asBoolean(false);
            copyExtra(n, f.extra, "id", "name", "chats", "favorite");
            folders.add(f);
        }
    }

    private static Entry readEntry(JsonNode n) {
        Entry e = new Entry();
        for (Flag f : Flag.values()) if (n.path(f.json).isBoolean()) e.values.put(f, n.get(f.json).asBoolean());
        for (JsonNode s : n.path("fromServer")) {
            for (Flag f : Flag.values()) if (f.json.equals(s.asText()) && e.values.containsKey(f)) e.fromServer.add(f);
        }
        if (n.has("reminderAt")) {
            e.reminderSet = true;
            try {
                e.reminder = n.get("reminderAt").isNull() ? null : Instant.parse(n.get("reminderAt").asText());
            } catch (RuntimeException bad) {
                e.reminder = null;
            }
        }
        List<String> known = new ArrayList<>(List.of("fromServer", "reminderAt"));
        for (Flag f : Flag.values()) known.add(f.json);
        copyExtra(n, e.extra, known.toArray(String[]::new));
        return e;
    }

    private static void copyExtra(JsonNode from, ObjectNode to, String... known) {
        Set<String> skip = Set.of(known);
        for (Iterator<Map.Entry<String, JsonNode>> it = from.fields(); it.hasNext(); ) {
            var f = it.next();
            if (!skip.contains(f.getKey())) to.set(f.getKey(), f.getValue());
        }
    }

    /**
     * Keeps the unreadable file so the next write can't destroy it. With nothing recovered it's renamed. With a recovery
     * it's copied, and stays until the recovered content replaces it: if that never happens, the next start recovers again.
     */
    private void setAsideBadFile() {
        try {
            if (recovered) {
                setAside = copyBadFile();
                damagedInPlace = true;
            } else {
                String name = freeAsideName();
                Files.move(file, file.resolveSibling(name));
                setAside = name;
            }
            unreadable = false;
        } catch (IOException e) {
            System.err.println("Could not set aside the unreadable preferences: " + e);
        }
    }

    /** The name of the copy on disk; repeated failed recoveries of the same damage share one. */
    private String copyBadFile() throws IOException {
        try (var copies = Files.newDirectoryStream(file.resolveSibling(""), file.getFileName() + ".bad-*")) {
            for (Path copy : copies) {
                if (Files.isRegularFile(copy) && Files.mismatch(file, copy) == -1) return copy.getFileName().toString();
            }
        }
        String name = freeAsideName();
        Path copy = file.resolveSibling(name);
        try {
            SyncedFile.write(copy, Files.readAllBytes(file));
        } catch (IOException e) {
            deleteQuietly(copy);
            throw e;
        }
        return name;
    }

    private String freeAsideName() {
        String stamp = file.getFileName() + ".bad-" + java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss-SSS").format(java.time.LocalDateTime.now());
        String name = stamp;
        for (int n = 2; Files.exists(file.resolveSibling(name)); n++) name = stamp + "-" + n;
        return name;
    }

    private void loadSeen() {
        if (!Files.exists(seenFile)) return;
        try {
            JsonNode root = JSON.readTree(seenFile.toFile());
            server = root.path("server").asText(null);
            for (Iterator<Map.Entry<String, JsonNode>> it = root.path("seen").fields(); it.hasNext(); ) {
                var c = it.next();
                Set<Flag> flags = EnumSet.noneOf(Flag.class);
                for (JsonNode s : c.getValue()) for (Flag f : Flag.values()) if (f.json.equals(s.asText())) flags.add(f);
                if (!flags.isEmpty()) seen.put(c.getKey(), flags);
            }
        } catch (IOException | RuntimeException e) {
            seen.clear();
        }
    }

    private void saveLater() {
        if (file == null) return;
        writePending = true;
        if (writerRunning) return;
        writerRunning = true;
        Thread.ofPlatform().daemon().name("preferences-save").start(this::writeWhilePending);
    }

    private void writeWhilePending() {
        boolean more = true;
        while (more) {
            try {
                writeOnce();
            } finally {
                // Decided in one step: a change between "nothing more" and "stopped" would start no writer and wait forever.
                synchronized (this) {
                    more = writePending;
                    if (!more) {
                        writerRunning = false;
                        notifyAll();
                    }
                }
            }
        }
    }

    /** A copy under the lock, the disk outside it, the outcome under it again. A change made meanwhile asks for another round. */
    private void writeOnce() {
        ObjectNode main = null, seenNow = null;
        boolean keepPrevious = false;
        synchronized (this) {
            writePending = false;
            if (unreadable) setAsideBadFile();
            if (dirty && !unreadable) {
                main = tree();
                keepPrevious = !damagedInPlace;
                dirty = false;
            }
            if (seenDirty) {
                seenNow = seenTree();
                seenDirty = false;
            }
        }
        boolean mainSaved = main == null || write(file, main, keepPrevious);
        boolean seenSaved = seenNow == null || write(seenFile, seenNow, false);
        synchronized (this) {
            if (main != null && mainSaved) {
                damagedInPlace = false;
                mainWrites++;
            }
            if (!mainSaved) dirty = true;
            if (!seenSaved) seenDirty = true;
            boolean failing = !mainSaved || !seenSaved || (dirty && unreadable);
            if (failing && !saveFailing && onSaveFailure != null) announce(onSaveFailure);
            saveFailing = failing;
        }
    }

    private void writeMain() {
        if (!write(file, tree(), !damagedInPlace)) return;
        dirty = false;
        damagedInPlace = false;
    }

    private ObjectNode tree() {
        ObjectNode root = JSON.createObjectNode().put("version", VERSION);
        root.setAll(extraRoot);
        ObjectNode cs = root.putObject("chats");
        chats.forEach((k, e) -> cs.set(k, node(e)));
        ArrayNode fs = root.putArray("folders");
        for (MutableFolder f : folders) {
            ObjectNode n = fs.addObject().put("id", f.id).put("name", f.name);
            if (f.favorite) n.put("favorite", true);
            ArrayNode keys = n.putArray("chats");
            f.keys.forEach(keys::add);
            n.setAll(f.extra);
        }
        return root;
    }

    private static ObjectNode node(Entry e) {
        ObjectNode n = JSON.createObjectNode();
        for (Flag f : Flag.values()) if (e.values.containsKey(f)) n.put(f.json, e.values.get(f));
        if (e.reminderSet) n.put("reminderAt", e.reminder == null ? null : e.reminder.toString());
        if (!e.fromServer.isEmpty()) {
            ArrayNode a = n.putArray("fromServer");
            for (Flag f : Flag.values()) if (e.fromServer.contains(f)) a.add(f.json);
        }
        n.setAll(e.extra);
        return n;
    }

    private ObjectNode seenTree() {
        ObjectNode root = JSON.createObjectNode().put("version", VERSION);
        if (server != null) root.put("server", server);
        ObjectNode s = root.putObject("seen");
        seen.forEach((k, flags) -> {
            ArrayNode a = s.putArray(k);
            for (Flag f : Flag.values()) if (flags.contains(f)) a.add(f.json);
        });
        return root;
    }

    /** A failure or a power cut leaves the old file whole. */
    private static boolean write(Path target, ObjectNode content, boolean keepPrevious) {
        try {
            Files.createDirectories(target.getParent());
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(content);
            if (keepPrevious) keepPrevious(target);
            SyncedFile.replace(target, bytes, false);
            return true;
        } catch (IOException | RuntimeException | Error e) { // out of memory included: the change stays pending
            System.err.println("Could not save " + target.getFileName() + ": " + e);
            return false;
        }
    }

    /** A backup that can't be made (Windows may hold the file) only costs the backup: the save goes on. */
    private static void keepPrevious(Path target) {
        try {
            if (Files.exists(target)) SyncedFile.replace(backupOf(target), Files.readAllBytes(target), false);
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not back up " + target.getFileName() + ": " + e);
        }
    }

    private static void deleteQuietly(Path file) {
        try { Files.deleteIfExists(file); } catch (IOException ignored) { /* nothing more to do */ }
    }
}

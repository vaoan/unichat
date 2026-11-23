package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.Errors;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The account's folder and file names, the ones before English ("Multimedia", "Ajustes", "Textos", "Bóveda"…) and the
 * ones now ({@link CloudAccount#MEDIA}, {@link CloudAccount#SETTINGS_DIR}, {@link TextSync#ROOT}, {@link Vault#ROOT}…), and
 * the move from one to the other, in place, at the start of every round: on the server, so nothing goes up again;
 * idempotent, so a move cut short anywhere is finished by the next round; and every round, since a computer still on an
 * older Unichat keeps writing the old names. The vault's own move is {@link VaultNames}'s (it needs the vault's key).
 * <p>
 * Names from the account are untrusted: only ours (a machine's UUID, a file {@link TextSync} makes, a {@link Cloud.Part}
 * file) are moved or deleted, and nothing else in an old folder is touched.
 */
final class CloudNames {
    static final String OLD_MEDIA = CloudAccount.ROOT + "/Multimedia", OLD_SETTINGS = CloudAccount.ROOT + "/Ajustes",
        OLD_TEXTS = CloudAccount.ROOT + "/Textos", OLD_VAULT = CloudAccount.ROOT + "/Bóveda";
    static final String OLD_OFF = OLD_SETTINGS + "/boveda-apagada.json";
    static final String OLD_INFO = OLD_VAULT + "/boveda.json", OLD_SLOTS = OLD_VAULT + "/llaves";
    /**
     * Machine-only: when this computer's first move was done (what turns up under an old name after it was written by an
     * older Unichat), and, per settings file under the old name, its content's hash and stamp when last looked at.
     */
    static final String AT = "cloud.names.at", SEEN = "cloud.names.seen.";

    /** Under {@code Unichat/}: each old name and its new one, a file's before its folder's. */
    private static final String[][] LOGICAL = {
        {"Ajustes/boveda-apagada.json", "Settings/vault-off.json"}, {"Registro/remitentes.json", "Log/senders.json"},
        {"Registro", "Log"}, {"Ajustes", "Settings"}, {"Textos", "Texts"}, {"Multimedia", "Media"}};
    /** Under the vault's folder: its own files' old names and new ones; the rest is named as {@link #LOGICAL} says. */
    private static final String[][] IN_VAULT = {{"llaves", "keys"}, {"boveda.json", "vault.json"}};

    private CloudNames() {}

    /** {@code path} (under {@code Unichat/}) with its old names made new, or as it is when it has none. */
    static String renamed(String path) {
        return swap(path, CloudAccount.ROOT, CloudAccount.ROOT, LOGICAL, 0, 1);
    }

    /** {@code path} (under {@code Unichat/}) as it was named before: what a file of the vault was sealed under then. */
    static String old(String path) {
        return swap(path, CloudAccount.ROOT, CloudAccount.ROOT, LOGICAL, 1, 0);
    }

    /** A path under {@link Vault#ROOT} where the vault kept it before ({@link #OLD_VAULT}); any other path as it is. */
    static String oldStored(String path) {
        String rest = under(path, Vault.ROOT);
        if (rest == null) return path;
        String own = swap(rest, null, null, IN_VAULT, 1, 0);
        return OLD_VAULT + (rest.isEmpty() ? "" : "/" + (own.equals(rest) ? strip(old(CloudAccount.ROOT + "/" + rest)) : own));
    }

    /** A path under {@link #OLD_VAULT} where the vault keeps it now; any other path as it is. */
    static String newStored(String path) {
        String rest = under(path, OLD_VAULT);
        if (rest == null) return path;
        String own = swap(rest, null, null, IN_VAULT, 0, 1);
        return Vault.ROOT + (rest.isEmpty() ? "" : "/" + (own.equals(rest) ? strip(renamed(CloudAccount.ROOT + "/" + rest)) : own));
    }

    private static String strip(String path) {
        return path.substring(CloudAccount.ROOT.length() + 1);
    }

    /** What's after {@code root + "/"} in {@code path} ("" for the root itself), or null when it isn't under it. */
    private static String under(String path, String root) {
        if (path.equals(root)) return "";
        return path.startsWith(root + "/") ? path.substring(root.length() + 1) : null;
    }

    /** The first entry of {@code table} whose {@code from} name {@code path}'s rest is, or starts as, swapped for its {@code to}. */
    private static String swap(String path, String root, String toRoot, String[][] table, int from, int to) {
        String rest = root == null ? path : under(path, root);
        if (rest == null) return path;
        for (String[] names : table) {
            String name = names[from];
            if (rest.equals(name) || rest.startsWith(name + "/")) {
                String swapped = names[to] + rest.substring(name.length());
                return root == null ? swapped : toRoot + "/" + swapped;
            }
        }
        return path;
    }

    /**
     * What the new name is to hold when both names hold a file at {@code path} (its new name): null when the new one's
     * already does. A text holds all of a shorter one (they only grow), an index the larger size of each file, a part
     * whose two copies differ the newer one ({@link Bundle#savedAt}); anything else the old one when {@code oldLive} (the
     * old names are still the ones in use), else the new one.
     */
    static byte[] winner(String path, byte[] old, byte[] now, boolean oldLive) throws IOException {
        if (now == null) return old;
        if (Arrays.equals(old, now)) return null;
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (path.startsWith(TextSync.ROOT + "/") && name.equals(TextIndex.NAME)) {
            Map<String, Long> merged = new TreeMap<>(TextIndex.read(now));
            TextIndex.read(old).forEach((file, size) -> merged.merge(file, size, Math::max));
            return merged.equals(TextIndex.read(now)) ? null : TextIndex.write(merged);
        }
        if (TextIndex.FILE.matcher(name).matches()) return old.length > now.length ? old : null;
        Bundle a = Bundle.parse(old), b = Bundle.parse(now);
        if (a != null && b != null) return a.hash().equals(b.hash()) || !a.savedAt().isAfter(b.savedAt()) ? null : old;
        return oldLive ? old : null;
    }

    /**
     * The plain settings and texts under their new names: run first in every round (the vault's own state is read after),
     * with {@code texts} (TextSync's lock) held over the texts, as each computer writes its own. The machines found still
     * writing the old names since this computer's first move: an older Unichat on another computer.
     */
    static Set<String> migrate(Storage dav, String machine, Object texts, Instant now) throws IOException {
        Settings s = Settings.get();
        Instant since = first(s);
        Set<String> older = new TreeSet<>();
        settings(dav, machine, since, older);
        synchronized (texts) {
            texts(dav, machine, since, older);
        }
        if (since == null) s.set(AT, now.toString());
        return older;
    }

    /**
     * Each settings file under {@link #OLD_SETTINGS}, taken in where the new name has none or an older one, and left where
     * it is, whoever wrote it: a computer still on an older Unichat reads it back as the account's copy, and one it found
     * gone it would send again from its own, with a new time that wins over what changed since. What was taken in is
     * remembered ({@link #SEEN}: its content's hash and stamp), so the same content sent again is no news. The turn-off
     * mark moves.
     */
    private static void settings(Storage dav, String machine, Instant since, Set<String> older) throws IOException {
        List<String> names = dav.list(OLD_SETTINGS);
        if (names.isEmpty()) {
            if (dav.exists(OLD_SETTINGS)) dav.delete(OLD_SETTINGS);
            return;
        }
        for (String name : names) {
            String path = OLD_SETTINGS + "/" + name;
            if (path.equals(OLD_OFF)) {
                if (!dav.exists(VaultOff.FILE)) moveInto(dav, path, VaultOff.FILE);
                else dav.delete(path);
                continue;
            }
            if (Cloud.Part.of(name) == null) continue;
            String stamp = dav.stamp(path);
            String[] seen = seen(name);
            if (stamp == null || seen != null && stamp.equals(seen[1])) continue; // gone meanwhile, or looked at already
            byte[] theirs = dav.get(path);
            Bundle b = theirs == null ? null : Bundle.parse(theirs);
            if (b == null) continue; // not a part of ours: left
            if (seen == null || !b.hash().equals(seen[0])) {
                if (since != null && b.savedAt().isAfter(since) && Ids.ours(b.machine()) && !b.machine().equals(machine)) older.add(b.machine());
                String target = renamed(path);
                byte[] wins = winner(target, theirs, dav.get(target), false);
                if (wins != null) {
                    dav.mkdirs(CloudAccount.SETTINGS_DIR);
                    dav.put(target, wins);
                }
            }
            Settings.get().set(SEEN + name, b.hash() + " " + stamp);
        }
    }

    /** What {@link #SEEN} says of {@code name}: its hash and stamp when last looked at, or null. */
    private static String[] seen(String name) {
        String kept = s(SEEN + name);
        String[] parts = kept == null ? null : kept.split(" ", 2);
        return parts == null || parts.length < 2 ? null : parts;
    }

    /**
     * Every machine's texts under {@link #OLD_TEXTS}: the whole folder moved when the new one isn't there; else each
     * machine's folder, or each of its files, the longer of two kept and both indexes merged. Since this computer's first
     * move, a machine's old folder is an older Unichat writing it.
     */
    private static void texts(Storage dav, String machine, Instant since, Set<String> older) throws IOException {
        List<String> machines = dav.list(OLD_TEXTS);
        if (machines.isEmpty()) {
            if (dav.exists(OLD_TEXTS)) dav.delete(OLD_TEXTS);
            return;
        }
        if (since != null) {
            for (String other : machines) if (Ids.ours(other) && !other.equals(machine)) older.add(other);
        }
        if (!dav.exists(TextSync.ROOT)) {
            moveInto(dav, OLD_TEXTS, TextSync.ROOT);
            return;
        }
        for (String other : machines) {
            if (!Ids.ours(other)) continue;
            String from = OLD_TEXTS + "/" + other, to = TextSync.ROOT + "/" + other;
            if (!dav.exists(to)) {
                dav.move(from, to);
                continue;
            }
            for (String name : dav.list(from)) {
                if (name.equals(TextIndex.NAME) || TextIndex.FILE.matcher(name).matches()) text(dav, from + "/" + name, to + "/" + name);
            }
            deleteIfEmpty(dav, from);
        }
        deleteIfEmpty(dav, OLD_TEXTS);
    }

    /** One text file or index under both names: the new name holds what wins, the old one goes. */
    private static void text(Storage dav, String from, String to) throws IOException {
        byte[] old = dav.get(from);
        if (old == null) return;
        byte[] now = dav.get(to);
        if (now == null) {
            dav.move(from, to);
            return;
        }
        byte[] wins = winner(to, old, now, false);
        if (wins == old) { // the old one whole: moved, not sent again
            dav.delete(to);
            dav.move(from, to);
            return;
        }
        if (wins != null) dav.put(to, wins);
        dav.delete(from);
    }

    /**
     * Media under {@link CloudAccount#MEDIA}: the whole folder moved when the new one isn't there; else each chat's folder,
     * or each of its files. One the new folder has too goes only with {@code dedupe} (this computer's first move, no older
     * Unichat known to write the old name) and once its size, and checksum where the service gives one, is the same:
     * otherwise it stays (an older computer's daily check would only send it again) and one that differs is said in the
     * log. Run before media, every round; a failure is said in the log, never stops media. Whether all of it is under the
     * new name or kept on purpose: else the round's check waits, as it would send again what's still under the old one.
     */
    static boolean media(Storage dav, boolean dedupe) {
        try {
            if (!dav.exists(OLD_MEDIA)) return true;
            if (!dav.exists(CloudAccount.MEDIA)) {
                moveInto(dav, OLD_MEDIA, CloudAccount.MEDIA);
                return true;
            }
            boolean all = true;
            for (String folder : dav.list(OLD_MEDIA)) {
                if (!Storage.plainName(folder)) continue;
                String from = OLD_MEDIA + "/" + folder, to = CloudAccount.MEDIA + "/" + folder;
                try {
                    if (!dav.exists(to)) {
                        dav.move(from, to);
                        continue;
                    }
                    for (String name : dav.list(from)) {
                        if (Storage.plainName(name)) mediaFile(dav, from + "/" + name, to + "/" + name, dedupe);
                    }
                    deleteIfEmpty(dav, from);
                } catch (IOException e) {
                    all = false;
                    System.err.println("Cloud: the media folder's new name: " + Errors.message(e)); // the next round goes on from there
                }
            }
            deleteIfEmpty(dav, OLD_MEDIA);
            return all;
        } catch (IOException | RuntimeException e) {
            System.err.println("Cloud: the media folder's new name: " + Errors.message(e));
            return false;
        }
    }

    private static void mediaFile(Storage dav, String from, String to, boolean dedupe) throws IOException {
        Storage.RemoteFile there = dav.info(to);
        if (there == null) {
            dav.move(from, to);
            return;
        }
        Storage.RemoteFile old = dav.info(from);
        if (old == null) return;
        if (!same(old, there)) System.err.println("Cloud: a file of the media is under both folder names, and they differ: both kept");
        else if (dedupe) dav.delete(from);
    }

    /** The same file, as far as the account can say: its size, and its checksum where both have one of a kind. */
    private static boolean same(Storage.RemoteFile a, Storage.RemoteFile b) {
        if (a.size() < 0 || a.size() != b.size()) return false;
        return a.kind() == null || b.kind() == null || !a.kind().equals(b.kind()) || a.hash().equalsIgnoreCase(b.hash());
    }

    /** {@code from} moved to {@code to}, the folder above it made first. */
    static void moveInto(Storage dav, String from, String to) throws IOException {
        dav.mkdirs(to.substring(0, to.lastIndexOf('/')));
        dav.move(from, to);
    }

    /** {@code dir} deleted when nothing's left in it. */
    static void deleteIfEmpty(Storage dav, String dir) throws IOException {
        if (dav.list(dir).isEmpty() && dav.exists(dir)) dav.delete(dir);
    }

    /** When this computer's first move was done; null before it. */
    private static Instant first(Settings s) {
        return instant(s.getString(AT, null));
    }

    /** Whether this computer's first move is still to come: nothing an older Unichat wrote since is known. */
    static boolean firstMove() {
        return first(Settings.get()) == null;
    }

    private static String s(String key) {
        return Settings.get().getString(key, null);
    }

    private static Instant instant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}

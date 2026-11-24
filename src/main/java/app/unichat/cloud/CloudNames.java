package app.unichat.cloud;

import app.unichat.settings.Settings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * The account's folder and file names, the ones before English ("Multimedia", "Ajustes", "Textos", "Bóveda"…) and the
 * ones now ({@link CloudAccount#MEDIA}, {@link CloudAccount#SETTINGS_DIR}, {@link TextSync#ROOT}, {@link Vault#ROOT}…), and
 * the move from one to the other, in place, once: on the server, so nothing goes up again; idempotent, so a move cut
 * short anywhere is finished by the next round; each old folder deleted whole once every file in it is confirmed under
 * its new name. The vault's own move is {@link VaultNames}'s (it needs the vault's key).
 * <p>
 * Names from the account are untrusted: only ours (a machine's UUID, a file {@link TextSync} makes, a {@link Cloud.Part}
 * file) are moved or deleted, and nothing else in an old folder is touched.
 */
final class CloudNames {
    static final String OLD_MEDIA = CloudAccount.ROOT + "/Multimedia";
    static final String OLD_SETTINGS = CloudAccount.ROOT + "/Ajustes";
    static final String OLD_TEXTS = CloudAccount.ROOT + "/Textos";
    static final String OLD_VAULT = CloudAccount.ROOT + "/Bóveda";
    static final String OLD_OFF = OLD_SETTINGS + "/boveda-apagada.json";
    static final String OLD_INFO = OLD_VAULT + "/boveda.json";
    static final String OLD_SLOTS = OLD_VAULT + "/llaves";
    /** Machine-only: this computer found every plain old folder moved and deleted, so it never looks for them again. */
    static final String DONE = "cloud.names.done";
    /**
     * Machine-only: a media file kept under the old name (it differs from the new one), with both copies' stamps when
     * found: while neither changes, the old folder isn't walked again.
     */
    static final String KEPT = "cloud.names.kept";

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
        Bundle a = Bundle.parse(old);
        Bundle b = Bundle.parse(now);
        if (a != null && b != null) return a.hash().equals(b.hash()) || !a.savedAt().isAfter(b.savedAt()) ? null : old;
        return oldLive ? old : null;
    }

    /**
     * The plain old folders under their new names, then deleted once confirmed: run at the start of each round until it's
     * done ({@link #DONE}), then never again. With {@code texts} (TextSync's lock) held over the texts, as each computer
     * writes its own. Cut short anywhere, the next round goes on from what's there; done without a failure, no media
     * file is missing under the new name (what's kept under the old one differs from its new copy).
     */
    static void migrate(Storage dav, Object texts) throws IOException {
        Settings s = Settings.get();
        if (s.getBool(DONE, false)) return;
        boolean gone = settings(dav);
        synchronized (texts) {
            gone &= texts(dav);
        }
        if (media(dav) && gone) s.setBool(DONE, true);
    }

    /**
     * Whether a settings part may still be under its old name ({@link #DONE} not written, and the old folder holds one,
     * or can't be listed).
     */
    static boolean settingsLeft(Storage dav) {
        if (Settings.get().getBool(DONE, false)) return false;
        try {
            return dav.list(OLD_SETTINGS).stream().anyMatch(name -> Cloud.Part.of(name) != null);
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /** Each settings file and the turn-off mark under {@link #OLD_SETTINGS}: moved, or the newer kept where both are. */
    private static boolean settings(Storage dav) throws IOException {
        for (String name : dav.list(OLD_SETTINGS)) {
            String path = OLD_SETTINGS + "/" + name;
            if (!path.equals(OLD_OFF) && Cloud.Part.of(name) == null) continue; // not ours: left, and the folder kept
            String target = renamed(path);
            byte[] old = dav.get(path);
            byte[] now = dav.get(target);
            if (old == null) continue;
            if (now == null) {
                moveInto(dav, path, target);
                continue;
            }
            byte[] wins = winner(target, old, now, false);
            if (wins != null) dav.put(target, wins);
        }
        return confirmed(dav, OLD_SETTINGS, 1) == null;
    }

    /**
     * Every machine's texts under {@link #OLD_TEXTS}: the whole folder moved when the new one isn't there; else each
     * machine's folder, or each of its files, the longer of two kept and both indexes merged.
     */
    private static boolean texts(Storage dav) throws IOException {
        if (!dav.exists(OLD_TEXTS)) return true;
        if (!dav.exists(TextSync.ROOT)) {
            moveInto(dav, OLD_TEXTS, TextSync.ROOT);
            return true;
        }
        for (String machine : dav.list(OLD_TEXTS)) {
            if (!Ids.ours(machine)) continue;
            String from = OLD_TEXTS + "/" + machine;
            String to = TextSync.ROOT + "/" + machine;
            if (!dav.exists(to)) {
                dav.move(from, to);
                continue;
            }
            for (String name : dav.list(from)) {
                if (name.equals(TextIndex.NAME) || TextIndex.FILE.matcher(name).matches()) text(dav, from + "/" + name, to + "/" + name);
            }
        }
        return confirmed(dav, OLD_TEXTS, 2) == null;
    }

    /** One text file or index under both names: the new name holds what wins (the old one whole is moved, not sent again). */
    private static void text(Storage dav, String from, String to) throws IOException {
        byte[] old = dav.get(from);
        byte[] now = dav.get(to);
        if (old == null) return;
        if (now == null) {
            dav.move(from, to);
            return;
        }
        byte[] wins = winner(to, old, now, false);
        if (wins == old) {
            dav.delete(to);
            dav.move(from, to);
        } else if (wins != null) {
            dav.put(to, wins);
        }
    }

    /**
     * Media under {@link CloudAccount#MEDIA}: the whole folder moved on the server; when both are there (a move cut
     * short), each chat's folder, or each of its files, the new folder lacks moved in. A file kept because it differs
     * ({@link #KEPT}) holds the folder without a walk each round, until one of its copies changes. Whether it's gone.
     */
    private static boolean media(Storage dav) throws IOException {
        if (!dav.exists(OLD_MEDIA)) return true;
        if (still(dav, Settings.get().getString(KEPT, null))) return false;
        if (!dav.exists(CloudAccount.MEDIA)) {
            moveInto(dav, OLD_MEDIA, CloudAccount.MEDIA);
            return true;
        }
        for (String folder : dav.list(OLD_MEDIA)) {
            if (!Storage.plainName(folder)) continue;
            String from = OLD_MEDIA + "/" + folder;
            String to = CloudAccount.MEDIA + "/" + folder;
            if (!dav.exists(to)) {
                dav.move(from, to);
                continue;
            }
            for (String name : dav.list(from)) {
                if (Storage.plainName(name) && dav.info(to + "/" + name) == null) dav.move(from + "/" + name, to + "/" + name);
            }
        }
        String kept = confirmed(dav, OLD_MEDIA, 2);
        Settings.get().set(KEPT, kept == null ? null : kept + "\n" + dav.stamp(kept) + "\n" + dav.stamp(renamed(kept)));
        return kept == null;
    }

    /** Whether the file {@link #KEPT} says and its new copy have the stamps they had then. */
    private static boolean still(Storage dav, String kept) throws IOException {
        String[] parts = kept == null ? new String[0] : kept.split("\n", 3);
        return parts.length == 3 && parts[1].equals(String.valueOf(dav.stamp(parts[0])))
            && parts[2].equals(String.valueOf(dav.stamp(renamed(parts[0]))));
    }

    /**
     * {@code dir} deleted whole once every file left in it ({@code depth} folders down) is held under its new name: media
     * the same size, and checksum where the service gives one (WebDAV gives only the size); anything else what wins
     * there ({@link #winner}). One that isn't is kept with its folder, and said in the log. Null once it's gone, else the
     * first path there that isn't held.
     */
    private static String confirmed(Storage dav, String dir, int depth) throws IOException {
        if (!dav.exists(dir)) return null;
        String kept = unconfirmed(dav, dir, depth);
        if (kept != null) {
            System.err.println("Cloud: " + dir + " kept under its old name: something in it isn't under the new one");
            return kept;
        }
        dav.delete(dir);
        return null;
    }

    /** The first path under {@code dir} that isn't held under its new name (a name not ours: its folder), or null. */
    private static String unconfirmed(Storage dav, String dir, int depth) throws IOException {
        for (String name : dav.list(dir)) {
            if (!Storage.plainName(name)) return dir;
            String path = dir + "/" + name;
            String kept = depth > 1 ? unconfirmed(dav, path, depth - 1) : held(dav, path) ? null : path;
            if (kept != null) return kept;
        }
        return null;
    }

    /** Whether the file at old {@code path} is held under its new name. */
    private static boolean held(Storage dav, String path) throws IOException {
        String target = renamed(path);
        if (path.startsWith(OLD_MEDIA + "/")) {
            Storage.RemoteFile old = dav.info(path);
            Storage.RemoteFile now = dav.info(target);
            return old != null && now != null && same(old, now);
        }
        byte[] old = dav.get(path);
        byte[] now = dav.get(target);
        return old != null && now != null && winner(target, old, now, false) == null;
    }

    /** The same file, as far as the account can say: its size, and its checksum where both have one of a kind. */
    @SuppressFBWarnings(value = "IMPROPER_UNICODE", justification = "compares two hex checksums the services give; hex is ASCII")
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

}

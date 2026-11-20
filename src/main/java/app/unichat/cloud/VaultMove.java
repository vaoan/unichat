package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.Errors;
import app.unichat.support.Hashes;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Moving in: what Unichat kept plain in the account before the vault (its files under {@code Ajustes}, every computer's
 * texts under {@code Textos}) goes into the vault, and each plain copy is deleted only once its sealed copy reads back
 * holding all of it. Run every round the vault is open here, so it's idempotent: nothing plain, nothing to do. Media never.
 * <p>
 * Each computer of the vault (one with a computer slot) is the only writer of its sealed texts ({@link TextSync} sends
 * them all again when the vault opens there): its plain ones are only let go once its sealed copy is as long. The texts
 * of a computer without a slot (before the vault, or still on a Unichat without it) have no other writer there: they're
 * brought in here.
 * <p>
 * Once this computer's move is done ({@link #MOVED_AT}), a plain file of a computer without a slot, or a settings file,
 * is a stray: a computer of the person's still running a Unichat without the vault. Its texts still come in (nothing is
 * lost) but its settings never; it's named, and its plain files go only once none has changed for {@link #QUIET}, so they
 * aren't deleted and sent again round after round. Names from the account are untrusted: only ours (a machine's UUID, a
 * file {@link TextSync} makes, an {@code unichat-<part>.json}) are touched.
 */
final class VaultMove {
    /**
     * Machine-only markers: when this computer's move was done, since when no stray changed, and what the strays' files
     * were then (their stamps' hash): kept here, so a restart doesn't make them look new.
     */
    static final String MOVED_AT = "cloud.vault.movedAt", QUIET_SINCE = "cloud.vault.strayQuietSince", STRAYS_SEEN = "cloud.vault.straySeen";
    static final Duration QUIET = Duration.ofDays(1);
    /** The machines sending plain texts when the move started (machine-only): only their folders are ever taken in. */
    static final String SENDERS = "cloud.vault.plainSenders";
    static final String SETTINGS_DIR = CloudAccount.SETTINGS_DIR;
    /** The files Unichat writes in {@link #SETTINGS_DIR}: no other name there is moved or deleted. */
    private static final Pattern OURS = Pattern.compile("unichat-[a-z]+\\.json");

    private VaultMove() {}

    /** A computer still sending plain, and when it last did: its settings' own time, or when its texts were found changed. */
    record Stray(String machine, Instant seen) {}

    /** What a round did: the plain paths it removed, the computers still sending plain (by machine), and the first failure (or null). */
    record Result(List<String> moved, List<Stray> strays, String error) {}

    /**
     * What a pass does: moving in (each file comes in where the vault lacks it, and goes once held), taking a stray's
     * texts in (nothing deleted, its settings untouched), or dropping a stray's plain files (its texts held first; its
     * settings, never taken, simply go).
     */
    private enum Pass { MOVE, TAKE, DROP }

    /**
     * What's plain of ours in the account: the settings files' paths, the text files' names of each computer without a
     * slot that was sending plain when the move started, those of each computer of the vault (left from before it opened
     * the vault there), and those of any other folder (put in through the account, or a computer that came later: named,
     * never taken).
     */
    private record Found(List<String> settings, Map<String, List<String>> texts, Map<String, List<String>> ofTheVault,
                         Map<String, List<String>> unknown) {
        boolean none() {
            return settings.isEmpty() && texts.isEmpty() && unknown.isEmpty();
        }
    }

    /** What a round removed, and the first thing that failed; and the vault moved into. */
    private static final class Tally {
        final List<String> moved = new ArrayList<>();
        final String vault;
        String error;

        Tally(String vault) {
            this.vault = vault;
        }

        boolean failed(IOException e) {
            if (error == null) error = Errors.message(e);
            return false;
        }

        Result result(List<Stray> strays) {
            return new Result(moved, strays, error);
        }
    }

    /** A vault other than the one moved into: everything about the move starts again. */
    static void forget() {
        Settings s = Settings.get();
        for (String k : new String[] {MOVED_AT, QUIET_SINCE, STRAYS_SEEN, SENDERS}) {
            if (s.getString(k, null) != null) s.set(k, null);
        }
    }

    /**
     * One round of it, with the vault open: {@code sealed} is the vault ({@code vaultId}) over {@code plain}. A file that fails stays plain
     * for the next round (the first failure is the result's error); only a listing that fails stops it.
     */
    static Result run(Storage plain, Storage sealed, String vaultId, Instant now) throws IOException {
        Settings s = Settings.get();
        Found found = find(plain);
        Tally t = new Tally(vaultId);
        boolean let = true;
        for (Map.Entry<String, List<String>> m : found.ofTheVault().entrySet()) {
            try {
                let &= letGo(plain, sealed, m.getKey(), m.getValue(), t);
            } catch (IOException e) {
                let = t.failed(e);
            }
        }
        if (s.getString(MOVED_AT, null) == null) {
            if (carry(plain, sealed, found, Pass.MOVE, t) && let) s.set(MOVED_AT, now.toString());
            return t.result(List.of());
        }
        if (found.none()) {
            s.set(QUIET_SINCE, null);
            s.set(STRAYS_SEEN, null);
            return t.result(List.of());
        }
        String seen = signature(plain, found);
        Instant quiet = instant(s.getString(QUIET_SINCE, null));
        if (quiet == null || !seen.equals(s.getString(STRAYS_SEEN, null))) {
            quiet = now; // something new from a stray: a day from now, and what it wrote comes in
            s.set(QUIET_SINCE, now.toString());
            s.set(STRAYS_SEEN, seen);
            carry(plain, sealed, found, Pass.TAKE, t);
        }
        if (now.isBefore(quiet.plus(QUIET))) return t.result(strays(plain, found, quiet));
        carry(plain, sealed, found, Pass.DROP, t);
        Found left = find(plain);
        if (left.none()) {
            s.set(QUIET_SINCE, null);
            s.set(STRAYS_SEEN, null);
            return t.result(List.of());
        }
        s.set(STRAYS_SEEN, signature(plain, left)); // what failed to go is no news: the next round tries it again
        return t.result(strays(plain, left, quiet));
    }

    /** Each stray's name in the vault (its computer slot's label), "" for a computer that has none there. */
    static Map<String, String> names(Storage plain, List<Stray> strays) throws IOException {
        Map<String, String> labels = Vault.computers(plain);
        Map<String, String> out = new LinkedHashMap<>();
        for (Stray stray : strays) out.put(stray.machine(), labels.getOrDefault(stray.machine(), ""));
        return out;
    }

    /** The vault's computers are read only when some texts are plain: nothing plain costs no more than the listings. */
    private static Found find(Storage plain) throws IOException {
        List<String> settings = new ArrayList<>();
        for (String name : plain.list(SETTINGS_DIR)) {
            if (OURS.matcher(name).matches()) settings.add(SETTINGS_DIR + "/" + name);
        }
        Map<String, List<String>> texts = new TreeMap<>(), ofTheVault = new TreeMap<>(), unknown = new TreeMap<>();
        for (String machine : plain.list(TextSync.ROOT)) {
            if (!Ids.ours(machine)) continue;
            List<String> names = new ArrayList<>();
            for (String name : plain.list(TextSync.ROOT + "/" + machine)) {
                if (name.equals(TextIndex.NAME) || TextIndex.FILE.matcher(name).matches()) names.add(name);
            }
            if (!names.isEmpty()) texts.put(machine, names);
        }
        if (!texts.isEmpty()) {
            Set<String> computers = Vault.computers(plain).keySet();
            for (String machine : computers) {
                List<String> names = texts.remove(machine);
                if (names != null) ofTheVault.put(machine, names);
            }
        }
        Set<String> senders = senders(texts.keySet()); // recorded on the first pass, nothing plain or not
        for (String machine : List.copyOf(texts.keySet())) {
            if (!senders.contains(machine)) unknown.put(machine, texts.remove(machine));
        }
        return new Found(settings, texts, ofTheVault, unknown);
    }

    /**
     * The machines sending plain texts when the move started, recorded the first time it runs (from {@code now}, those
     * without a slot found then): only those were ever the person's, so only theirs come in.
     */
    private static Set<String> senders(Set<String> now) {
        Settings s = Settings.get();
        String kept = s.getString(SENDERS, null);
        if (kept == null) {
            kept = String.join(",", now);
            s.set(SENDERS, kept);
        }
        return kept.isEmpty() ? Set.of() : Set.of(kept.split(","));
    }

    /** One pass over what's plain; whether all of it was done. */
    private static boolean carry(Storage plain, Storage sealed, Found found, Pass pass, Tally t) {
        boolean all = true, delete = pass != Pass.TAKE;
        for (String path : found.settings()) {
            if (pass == Pass.TAKE) continue;
            try {
                all &= pass == Pass.DROP ? deleteIfSame(plain, path, plain.stamp(path), t) : settingIn(plain, sealed, path, t);
            } catch (IOException e) {
                all = t.failed(e);
            }
        }
        for (Map.Entry<String, List<String>> m : found.texts().entrySet()) {
            try {
                all &= textsIn(plain, sealed, m.getKey(), m.getValue(), delete, t);
            } catch (IOException e) {
                all = t.failed(e);
            }
        }
        return all;
    }

    /**
     * A settings file moving in: copied only where the vault has none (the round already wrote its own parts, newer);
     * the plain one goes once the vault's opens.
     */
    private static boolean settingIn(Storage plain, Storage sealed, String path, Tally t) throws IOException {
        String stamp = plain.stamp(path);
        byte[] mine = stamp == null ? null : plain.get(path);
        if (mine == null) return false; // gone meanwhile: the next round looks again
        byte[] kept = sealed.get(path);
        if (kept == null) {
            sealed.mkdirs(SETTINGS_DIR);
            sealed.put(path, mine);
            kept = sealed.get(path);
        }
        return kept != null && deleteIfSame(plain, path, stamp, t);
    }

    /**
     * A computer of the vault's plain texts, left from before: never written here. Each goes once its sealed copy, as its
     * computer sent it, reads back as long; its plain index last, once all of them went. Whether all of them went.
     */
    private static boolean letGo(Storage plain, Storage sealed, String machine, List<String> names, Tally t) throws IOException {
        String folder = TextSync.ROOT + "/" + machine, indexPath = folder + "/" + TextIndex.NAME;
        String indexStamp = plain.stamp(indexPath);
        Map<String, Long> theirs = TextIndex.read(indexStamp == null ? null : plain.get(indexPath));
        Map<String, Long> held = TextIndex.read(sealed.get(indexPath));
        boolean all = true;
        for (String name : names) {
            if (name.equals(TextIndex.NAME)) continue;
            Long size = theirs.get(name), have = held.get(name);
            if (have == null || size != null && have < size) { // not sent sealed yet: nothing is read until it is
                all = false;
                continue;
            }
            try {
                String path = folder + "/" + name, stamp = plain.stamp(path);
                byte[] mine = stamp == null ? null : plain.get(path), kept = mine == null ? null : sealed.get(path);
                all &= kept != null && kept.length >= mine.length && deleteIfSame(plain, path, stamp, t);
            } catch (IOException e) {
                all = t.failed(e);
            }
        }
        return all && (indexStamp == null || deleteIfSame(plain, indexPath, indexStamp, t));
    }

    /**
     * A computer without a slot's texts: each file the vault's index doesn't already show as long comes in where the
     * vault's is missing or shorter (they only grow), then the vault's index learns them; with {@code delete}, each plain
     * file goes once the vault's reads back as long, and the plain index last, once all of them went.
     */
    private static boolean textsIn(Storage plain, Storage sealed, String machine, List<String> names, boolean delete, Tally t)
        throws IOException {
        String folder = TextSync.ROOT + "/" + machine, indexPath = folder + "/" + TextIndex.NAME;
        String indexStamp = plain.stamp(indexPath);
        Map<String, Long> theirs = TextIndex.read(indexStamp == null ? null : plain.get(indexPath));
        Map<String, Long> held = TextIndex.read(sealed.get(indexPath));
        Map<String, Long> index = new TreeMap<>(held);
        boolean all = true;
        for (String name : names) {
            if (name.equals(TextIndex.NAME)) continue;
            Long size = theirs.get(name), have = held.get(name);
            if (!delete && size != null && have != null && have >= size) continue; // the vault already has as much
            try {
                long length = textIn(plain, sealed, folder + "/" + name, delete, t);
                if (length < 0) all = false;
                else index.merge(name, length, Math::max);
            } catch (IOException e) {
                all = t.failed(e);
            }
        }
        if (!index.equals(held)) {
            sealed.mkdirs(folder);
            sealed.put(indexPath, TextIndex.write(index));
            all &= TextIndex.read(sealed.get(indexPath)).equals(index);
        }
        if (!all || !delete || indexStamp == null) return all;
        return deleteIfSame(plain, indexPath, indexStamp, t);
    }

    /**
     * One text in, only as a new name (a plain file is never written over a sealed one: who wrote the plain one is the
     * account's to say): the vault's copy's length once it holds all of the plain one, -1 while it doesn't.
     */
    private static long textIn(Storage plain, Storage sealed, String path, boolean delete, Tally t) throws IOException {
        String stamp = plain.stamp(path);
        byte[] mine = stamp == null ? null : plain.get(path);
        if (mine == null) return -1;
        byte[] kept = sealed.get(path);
        if (kept == null) {
            sealed.mkdirs(path.substring(0, path.lastIndexOf('/')));
            sealed.put(path, mine);
            kept = sealed.get(path);
        }
        if (kept == null || kept.length < mine.length) return -1;
        return !delete || deleteIfSame(plain, path, stamp, t) ? kept.length : -1;
    }

    /**
     * Deletes the plain file unless the vault's latest recovery code isn't confirmed kept, it was written again since it
     * was read (an older computer sending a longer one), or
     * the vault moved into is gone meanwhile (turned off elsewhere: the plain copy is the only one). Asked right before
     * the delete; one request apart, a vault turned off in between still loses this copy (its writer sends its own again).
     */
    private static boolean deleteIfSame(Storage plain, String path, String stamp, Tally t) throws IOException {
        // Its code not yet confirmed kept: this computer may be the vault's only way in, and the plain copy the only one left.
        if (t.vault.equals(Settings.get().getString(VaultActions.UNCONFIRMED, null))) return false;
        if (stamp == null || !stamp.equals(plain.stamp(path))) return false;
        Vault.Info now = Vault.info(plain);
        if (now == null || !now.id().equals(t.vault)) return false;
        plain.delete(path);
        t.moved.add(path);
        return true;
    }

    /** What the strays' plain files are now, without reading them: each settings file's stamp, each machine's index's and names. */
    private static String signature(Storage plain, Found found) throws IOException {
        StringBuilder b = new StringBuilder();
        for (String path : found.settings()) b.append(path).append('=').append(plain.stamp(path)).append('\n');
        for (Map.Entry<String, List<String>> m : found.texts().entrySet()) {
            String folder = TextSync.ROOT + "/" + m.getKey();
            b.append(folder).append('=').append(plain.stamp(folder + "/" + TextIndex.NAME)).append(new TreeSet<>(m.getValue())).append('\n');
        }
        return Hashes.sha256(b.toString());
    }

    /** Who sends the plain files, by machine: a settings file says who wrote it; texts are under their machine's name. */
    private static List<Stray> strays(Storage plain, Found found, Instant quiet) throws IOException {
        Map<String, Instant> seen = new TreeMap<>();
        for (String path : found.settings()) {
            Bundle b = Bundle.parse(plain.get(path));
            if (b != null && Ids.ours(b.machine())) seen.merge(b.machine(), b.savedAt(), (x, y) -> x.isAfter(y) ? x : y);
        }
        for (String machine : found.texts().keySet()) seen.merge(machine, quiet, (x, y) -> x.isAfter(y) ? x : y);
        for (String machine : found.unknown().keySet()) seen.merge(machine, quiet, (x, y) -> x.isAfter(y) ? x : y);
        List<Stray> out = new ArrayList<>();
        seen.forEach((m, at) -> out.add(new Stray(m, at)));
        return out;
    }

    private static Instant instant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}

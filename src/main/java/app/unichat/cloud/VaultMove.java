package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.Errors;
import app.unichat.support.Hashes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Moving in: what Unichat kept plain in the account before the vault (its files under {@code Ajustes}, every computer's
 * texts under {@code Textos}) goes into the vault, and each plain copy is deleted only once its sealed copy reads back
 * holding all of it. Run every round the vault is open here, so it's idempotent: nothing plain, nothing to do. Media never.
 * <p>
 * Once this computer's move is done ({@link #MOVED_AT}), a plain file is a stray: a computer of the person's still running
 * a Unichat without the vault. Its texts still come in (nothing is lost) but its settings never over the vault's; it's
 * named, and its plain files go only once none has changed for {@link #QUIET}, so they aren't deleted and sent again
 * round after round. Names from the account are untrusted: only ours (a machine's UUID, a file {@link TextSync} makes, an
 * {@code unichat-<part>.json}) are touched.
 */
final class VaultMove {
    /**
     * Machine-only markers: when this computer's move was done, since when no stray changed, and what the strays' files
     * were then (their stamps' hash): kept here, so a restart doesn't make them look new.
     */
    static final String MOVED_AT = "cloud.vault.movedAt", QUIET_SINCE = "cloud.vault.strayQuietSince", STRAYS_SEEN = "cloud.vault.straySeen";
    static final Duration QUIET = Duration.ofDays(1);
    static final String SETTINGS_DIR = CloudAccount.ROOT + "/Ajustes";
    /** The files Unichat writes in {@link #SETTINGS_DIR}: no other name there is moved or deleted. */
    private static final Pattern OURS = Pattern.compile("unichat-[a-z]+\\.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private VaultMove() {}

    /** A computer still sending plain, and when it last did: its settings' own time, or when its texts were found changed. */
    record Stray(String machine, Instant seen) {}

    /** What a round did: the plain paths it removed, and the computers still sending plain (by machine). */
    record Result(List<String> moved, List<Stray> strays) {}

    /**
     * What a pass does: moving in (each file comes in where the vault lacks it, and goes once held), taking a stray's
     * texts in (nothing deleted, its settings untouched), or dropping a stray's plain files (its texts held first; its
     * settings, never taken, simply go).
     */
    private enum Pass { MOVE, TAKE, DROP }

    /** What's plain of ours in the account: the settings files' paths, and each machine's text files' names. */
    private record Found(List<String> settings, Map<String, List<String>> texts) {
        boolean none() {
            return settings.isEmpty() && texts.isEmpty();
        }
    }

    /**
     * One round of it, with the vault open: {@code sealed} is the vault over {@code plain}. A file that fails stays plain
     * for the next round; only a listing that fails stops it.
     */
    static Result run(Storage plain, Storage sealed, Instant now) throws IOException {
        Settings s = Settings.get();
        Found found = find(plain);
        List<String> moved = new ArrayList<>();
        if (s.getString(MOVED_AT, null) == null) {
            if (carry(plain, sealed, found, Pass.MOVE, moved)) s.set(MOVED_AT, now.toString());
            return new Result(moved, List.of());
        }
        if (found.none()) {
            s.set(QUIET_SINCE, null);
            s.set(STRAYS_SEEN, null);
            return new Result(moved, List.of());
        }
        String seen = signature(plain, found);
        Instant quiet = instant(s.getString(QUIET_SINCE, null));
        if (quiet == null || !seen.equals(s.getString(STRAYS_SEEN, null))) {
            quiet = now; // something new from a stray: a day from now, and what it wrote comes in
            s.set(QUIET_SINCE, now.toString());
            s.set(STRAYS_SEEN, seen);
            carry(plain, sealed, found, Pass.TAKE, moved);
        }
        if (now.isBefore(quiet.plus(QUIET))) return new Result(moved, strays(plain, found, quiet));
        carry(plain, sealed, found, Pass.DROP, moved);
        Found left = find(plain);
        if (left.none()) {
            s.set(QUIET_SINCE, null);
            s.set(STRAYS_SEEN, null);
            return new Result(moved, List.of());
        }
        s.set(STRAYS_SEEN, signature(plain, left)); // what failed to go is no news: the next round tries it again
        return new Result(moved, strays(plain, left, quiet));
    }

    /** Each stray's name in the vault (its computer slot's label), "" for a computer that has none there. */
    static Map<String, String> names(Storage plain, List<Stray> strays) throws IOException {
        Map<String, String> labels = new TreeMap<>();
        for (VaultSlot slot : Vault.slots(plain)) {
            if (slot.method() == VaultSlot.Method.COMPUTER) labels.put(slot.id(), slot.label());
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Stray stray : strays) out.put(stray.machine(), labels.getOrDefault(stray.machine(), ""));
        return out;
    }

    private static Found find(Storage plain) throws IOException {
        List<String> settings = new ArrayList<>();
        for (String name : plain.list(SETTINGS_DIR)) {
            if (OURS.matcher(name).matches()) settings.add(SETTINGS_DIR + "/" + name);
        }
        Map<String, List<String>> texts = new TreeMap<>();
        for (String machine : plain.list(TextSync.ROOT)) {
            if (!Ids.ours(machine)) continue;
            List<String> names = new ArrayList<>();
            for (String name : plain.list(TextSync.ROOT + "/" + machine)) {
                if (name.equals(TextSync.INDEX) || TextSync.FILE.matcher(name).matches()) names.add(name);
            }
            if (!names.isEmpty()) texts.put(machine, names);
        }
        return new Found(settings, texts);
    }

    /** One pass over what's plain; whether all of it was done. */
    private static boolean carry(Storage plain, Storage sealed, Found found, Pass pass, List<String> moved) {
        boolean all = true, delete = pass != Pass.TAKE;
        for (String path : found.settings()) {
            if (pass == Pass.TAKE) continue;
            try {
                all &= pass == Pass.DROP ? deleteIfSame(plain, path, plain.stamp(path), moved) : settingIn(plain, sealed, path, moved);
            } catch (IOException e) {
                all = failed(path, e);
            }
        }
        for (Map.Entry<String, List<String>> m : found.texts().entrySet()) {
            try {
                all &= textsIn(plain, sealed, m.getKey(), m.getValue(), delete, moved);
            } catch (IOException e) {
                all = failed(TextSync.ROOT + "/" + m.getKey(), e);
            }
        }
        return all;
    }

    private static boolean failed(String path, IOException e) {
        System.err.println("Cloud: vault: moving " + path + " in: " + Errors.message(e));
        return false;
    }

    /**
     * A settings file moving in: copied only where the vault has none (the round already wrote its own parts, newer);
     * the plain one goes once the vault's opens.
     */
    private static boolean settingIn(Storage plain, Storage sealed, String path, List<String> moved) throws IOException {
        String stamp = plain.stamp(path);
        byte[] mine = stamp == null ? null : plain.get(path);
        if (mine == null) return false; // gone meanwhile: the next round looks again
        byte[] kept = sealed.get(path);
        if (kept == null) {
            sealed.mkdirs(SETTINGS_DIR);
            sealed.put(path, mine);
            kept = sealed.get(path);
        }
        return kept != null && deleteIfSame(plain, path, stamp, moved);
    }

    /**
     * One machine's texts: each file the vault's index doesn't already show as long comes in where the vault's is
     * missing or shorter (they only grow), then the vault's index learns them; with {@code delete}, each plain file goes
     * once the vault's reads back as long, and the plain index last, once all of them went.
     */
    private static boolean textsIn(Storage plain, Storage sealed, String machine, List<String> names, boolean delete,
                                   List<String> moved) throws IOException {
        String folder = TextSync.ROOT + "/" + machine, indexPath = folder + "/" + TextSync.INDEX;
        String indexStamp = plain.stamp(indexPath);
        Map<String, Long> theirs = sizes(indexStamp == null ? null : plain.get(indexPath)), held = sizes(sealed.get(indexPath));
        Map<String, Long> index = new TreeMap<>(held);
        boolean all = true;
        for (String name : names) {
            if (name.equals(TextSync.INDEX)) continue;
            Long size = theirs.get(name), have = held.get(name);
            if (!delete && size != null && have != null && have >= size) continue; // the vault already has as much
            try {
                long length = textIn(plain, sealed, folder + "/" + name, delete, moved);
                if (length < 0) all = false;
                else index.merge(name, length, Math::max);
            } catch (IOException e) {
                all = failed(folder + "/" + name, e);
            }
        }
        if (!index.equals(held)) {
            ObjectNode n = JSON.createObjectNode();
            index.forEach(n.putObject("files")::put);
            sealed.mkdirs(folder);
            sealed.put(indexPath, JSON.writeValueAsBytes(n));
            all &= sizes(sealed.get(indexPath)).equals(index);
        }
        if (!all || !delete || indexStamp == null) return all;
        return deleteIfSame(plain, indexPath, indexStamp, moved);
    }

    /** One text in: the vault's copy's length once it holds all of the plain one, -1 while it doesn't. */
    private static long textIn(Storage plain, Storage sealed, String path, boolean delete, List<String> moved) throws IOException {
        String stamp = plain.stamp(path);
        byte[] mine = stamp == null ? null : plain.get(path);
        if (mine == null) return -1;
        byte[] kept = sealed.get(path);
        if (kept == null || kept.length < mine.length) {
            sealed.mkdirs(path.substring(0, path.lastIndexOf('/')));
            sealed.put(path, mine);
            kept = sealed.get(path);
        }
        if (kept == null || kept.length < mine.length) return -1;
        return !delete || deleteIfSame(plain, path, stamp, moved) ? kept.length : -1;
    }

    /** Deletes the plain file unless it was written again since it was read (an older computer sending a longer one). */
    private static boolean deleteIfSame(Storage plain, String path, String stamp, List<String> moved) throws IOException {
        if (stamp == null || !stamp.equals(plain.stamp(path))) return false;
        plain.delete(path);
        moved.add(path);
        return true;
    }

    /** An index's sizes, by name of ours; empty for none or one that isn't. */
    private static Map<String, Long> sizes(byte[] index) {
        Map<String, Long> out = new TreeMap<>();
        if (index == null) return out;
        try {
            JsonNode n = JSON.readTree(index);
            if (n == null) return out;
            n.path("files").properties().forEach(e -> {
                if (TextSync.FILE.matcher(e.getKey()).matches() && e.getValue().canConvertToLong()) out.put(e.getKey(), e.getValue().asLong());
            });
        } catch (IOException e) {
            out.clear();
        }
        return out;
    }

    /** What the strays' plain files are now, without reading them: each settings file's stamp, each machine's index's and names. */
    private static String signature(Storage plain, Found found) throws IOException {
        StringBuilder b = new StringBuilder();
        for (String path : found.settings()) b.append(path).append('=').append(plain.stamp(path)).append('\n');
        for (Map.Entry<String, List<String>> m : found.texts().entrySet()) {
            String folder = TextSync.ROOT + "/" + m.getKey();
            b.append(folder).append('=').append(plain.stamp(folder + "/" + TextSync.INDEX)).append(new java.util.TreeSet<>(m.getValue())).append('\n');
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

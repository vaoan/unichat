package app.unichat.cloud;

import app.unichat.support.Errors;
import app.unichat.support.I18n;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turning the vault off, the reverse of {@link VaultMove}: what the vault holds that has a plain place goes back there
 * (the settings and what the AI learned, and every computer's texts, those of one that isn't running too, since nobody
 * else would send them), each plain copy read back; only once all of them came back whole is {@link Vault#ROOT} deleted.
 * Anything failing before takes back the plain copies this run wrote (the vault holds them all) and leaves the vault as it
 * was, to try again. A file of the vault that doesn't open is left: nothing holding its key could read it. The vault's own
 * parts (secrets, pins, transcripts) never go plain: each computer keeps its own. Media is never touched. Names from the
 * account are untrusted: only ours (a machine's UUID, a file {@link TextSync} makes) become paths.
 * <p>
 * Another computer's round still open while this runs may let a plain copy go (its sealed one is as long): once the vault
 * is deleted nothing can, so every copy is looked at again then and put back where it's missing or shorter.
 */
final class VaultOut {
    /** The parts the plain round carries, at their logical paths. */
    private static final List<String> PARTS = List.of(Cloud.Part.SETTINGS.path(), Cloud.Part.AI.path());

    /** What a run left plain: each path's content, and what was there before each it wrote (null: nothing), to take it back. */
    private static final class Written {
        final Map<String, byte[]> now = new LinkedHashMap<>();
        final Map<String, byte @Nullable []> before = new LinkedHashMap<>();

        void put(Storage plain, String path, byte[] data, byte @Nullable [] was) throws IOException {
            plain.mkdirs(path.substring(0, path.lastIndexOf('/')));
            if (!before.containsKey(path)) before.put(path, was);
            now.put(path, data);
            plain.put(path, data);
        }

        /** Best effort: each plain file this run wrote as it was before. */
        void takeBack(Storage plain) {
            before.forEach((path, was) -> {
                try {
                    if (was == null) plain.delete(path);
                    else plain.put(path, was);
                } catch (IOException e) {
                    System.err.println("Cloud: a plain copy turning the vault off wrote couldn't be taken back: " + path);
                }
            });
        }
    }

    private VaultOut() {}

    /**
     * Everything back plain, then the mark that {@code vault} is turned off ({@link VaultOff}, so the other computers
     * believe it), then the vault deleted; {@code sealed} is the vault, open with {@code vk}, over {@code plain}. False when
     * its folder couldn't be deleted but its file was: no computer finds a vault, though some of it is left in the account.
     */
    @SuppressFBWarnings(value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION", justification = "the failure that stopped it is thrown again once the plain files it wrote are taken back")
    static boolean run(Storage plain, Storage sealed, Vault.Info vault, byte[] vk) throws IOException {
        Written w = new Written();
        try {
            for (String path : PARTS) partOut(plain, sealed, path, w);
            for (String machine : sealed.list(TextSync.ROOT)) {
                if (Ids.ours(machine)) textsOut(plain, sealed, machine, w);
            }
            w.before.putIfAbsent(VaultOff.FILE, null); // taken back too: a mark left with the vault still there would be believed
            VaultOff.write(plain, vault, vk);
        } catch (IOException e) {
            w.takeBack(plain);
            throw new IOException(I18n.t("cloud.error.vaultOffUndone", Errors.message(e)), e);
        } catch (RuntimeException e) {
            w.takeBack(plain);
            throw e;
        }
        boolean whole = true;
        IOException partly = null;
        try {
            plain.delete(Vault.ROOT);
        } catch (IOException e) {
            whole = false;
            try {
                plain.delete(Vault.INFO);
            } catch (IOException f) {
                // The vault may be partly gone: nothing is taken back, everything is plain already.
                partly = new IOException(I18n.t("cloud.error.vaultPartlyGone"), f);
                try {
                    VaultOff.delete(plain); // a vault still there isn't turned off: its file deleted later is no sign it was
                } catch (IOException g) {
                    partly.addSuppressed(g);
                }
            }
        }
        settle(plain, w);
        if (partly != null) throw partly;
        return whole;
    }

    /**
     * After the delete: each plain copy this run left, put back where it's missing (or, a text, shorter: texts only grow;
     * a part is a whole file, and another one there is a computer's own, newer), and read back.
     */
    private static void settle(Storage plain, Written w) throws IOException {
        for (Map.Entry<String, byte[]> f : new LinkedHashMap<>(w.now).entrySet()) {
            String path = f.getKey();
            byte[] there = plain.get(path);
            byte[] mine = f.getValue();
            boolean text = TextIndex.FILE.matcher(path.substring(path.lastIndexOf('/') + 1)).matches();
            if (there != null && (!text || there.length >= mine.length)) continue;
            w.put(plain, path, mine, there);
            if (!Arrays.equals(mine, plain.get(path))) throw notBack();
        }
    }

    /** A part as the vault holds it, in its plain place; none there (or one that doesn't open) is left. */
    private static void partOut(Storage plain, Storage sealed, String path, Written w) throws IOException {
        byte[] opened = openOrNull(sealed, path);
        if (opened == null) return;
        w.put(plain, path, opened, plain.get(path));
        if (!Arrays.equals(opened, plain.get(path))) throw notBack();
    }

    /**
     * A computer's texts, each written plain where the plain one is missing or shorter (they only grow), then the plain
     * index learning them. A text that doesn't open is left: its writer, once it finds the vault gone, sends all of its
     * own plain again.
     */
    private static void textsOut(Storage plain, Storage sealed, String machine, Written w) throws IOException {
        String folder = TextSync.ROOT + "/" + machine;
        String indexPath = folder + "/" + TextIndex.NAME;
        byte[] raw = plain.get(indexPath);
        Map<String, Long> had;
        try {
            had = TextIndex.read(raw);
        } catch (IOException e) {
            had = Map.of(); // not an index (someone else's file): written anew, never in the way of turning it off
        }
        Map<String, Long> index = new TreeMap<>(had);
        for (String name : sealed.list(folder)) {
            if (!TextIndex.FILE.matcher(name).matches()) continue;
            String path = folder + "/" + name;
            byte[] kept = openOrNull(sealed, path);
            if (kept == null) continue;
            byte[] mine = plain.get(path);
            if (mine == null || mine.length < kept.length) {
                w.put(plain, path, kept, mine);
                mine = plain.get(path);
                if (mine == null || mine.length < kept.length) throw notBack();
            } else {
                w.now.put(path, mine); // as long already: looked at again after the delete all the same
            }
            index.merge(name, (long) mine.length, Math::max);
        }
        if (index.equals(had)) {
            if (raw != null) w.now.put(indexPath, raw); // looked at again after the delete too
            return;
        }
        w.put(plain, indexPath, TextIndex.write(index), raw);
        if (!TextIndex.read(plain.get(indexPath)).equals(index)) throw notBack();
    }

    /** The vault's copy opened; null for none, or for one that doesn't open with this key. */
    private static byte @Nullable [] openOrNull(Storage sealed, String path) throws IOException {
        try {
            return sealed.get(path);
        } catch (SealedStorage.Damaged e) {
            return null;
        }
    }

    private static IOException notBack() {
        return new IOException(I18n.t("cloud.error.vaultNotBack"));
    }
}

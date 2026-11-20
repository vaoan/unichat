package app.unichat.cloud;

import app.unichat.support.I18n;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turning the vault off, the reverse of {@link VaultMove}: what the vault holds that has a plain place goes back there
 * (the settings and what the AI learned, and every computer's texts, those of one that isn't running too, since nobody
 * else would send them), each plain copy read back; only once all of them came back whole is {@link Vault#ROOT} deleted.
 * Anything failing before leaves the vault as it was, to try again. The vault's own parts (secrets, pins, transcripts)
 * never go plain: each computer keeps its own. Media is never touched. Names from the account are untrusted: only ours
 * (a machine's UUID, a file {@link TextSync} makes) become paths.
 */
final class VaultOut {
    /** The parts the plain round carries, at their logical paths. */
    private static final List<String> PARTS = List.of(CloudAccount.SETTINGS_FILE, CloudAccount.ROOT + "/Ajustes/unichat-ai.json");

    private VaultOut() {}

    /** Everything back plain, then the vault deleted; {@code sealed} is the vault, open, over {@code plain}. */
    static void run(Storage plain, Storage sealed) throws IOException {
        for (String path : PARTS) partOut(plain, sealed, path);
        for (String machine : sealed.list(TextSync.ROOT)) {
            if (Ids.ours(machine)) textsOut(plain, sealed, machine);
        }
        plain.delete(Vault.ROOT);
    }

    /** A part as the vault holds it, in its plain place; none there (or one that doesn't open: no one can read it) is left. */
    private static void partOut(Storage plain, Storage sealed, String path) throws IOException {
        byte[] opened = sealed.get(path);
        if (opened == null) return;
        plain.mkdirs(path.substring(0, path.lastIndexOf('/')));
        plain.put(path, opened);
        if (!Arrays.equals(opened, plain.get(path))) throw notBack();
    }

    /**
     * A computer's texts, each written plain where the plain one is missing or shorter (they only grow), then the plain
     * index learning them. A text that doesn't open is left: nothing holding the vault's key could read it, and its writer,
     * once it finds the vault gone, sends all of its own plain again.
     */
    private static void textsOut(Storage plain, Storage sealed, String machine) throws IOException {
        String folder = TextSync.ROOT + "/" + machine, indexPath = folder + "/" + TextIndex.NAME;
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
            byte[] kept;
            try {
                kept = sealed.get(path);
            } catch (SealedStorage.Damaged e) {
                continue;
            }
            if (kept == null) continue;
            byte[] mine = plain.get(path);
            if (mine == null || mine.length < kept.length) {
                plain.mkdirs(folder);
                plain.put(path, kept);
                mine = plain.get(path);
                if (mine == null || mine.length < kept.length) throw notBack();
            }
            index.merge(name, (long) mine.length, Math::max);
        }
        if (index.equals(had)) return;
        plain.mkdirs(folder);
        plain.put(indexPath, TextIndex.write(index));
        if (!TextIndex.read(plain.get(indexPath)).equals(index)) throw notBack();
    }

    private static IOException notBack() {
        return new IOException(I18n.t("cloud.error.vaultNotBack"));
    }
}

package app.unichat.cloud;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Every file the vault keeps, sealed again from an old vault key to a new one: all of them by the computer that changed
 * the key, and by each one as it takes the new key only what it sent itself (it may have sent it with the old key
 * meanwhile; anything else opening only with the old key may be a removed computer's, which nobody takes in). A file
 * that already opens with the new key is left; one that opens with neither, or isn't this computer's, is left and
 * named; one that can't be read or listed is noted and the rest goes on.
 */
final class VaultReseal {
    private static final String PREFIX = Vault.ROOT + "/";

    private VaultReseal() {}

    /** What was left: opening with neither key (or not this computer's to seal), and what couldn't be read or listed. */
    record Result(List<String> left, List<String> failed) {}

    /** Every file. */
    static Result run(Storage s, byte[] oldVk, byte[] newVk) {
        return run(s, oldVk, newVk, (path, plain) -> true);
    }

    /** Only the files {@code ours} says this computer sent (by its logical path and opened content). */
    static Result run(Storage s, byte[] oldVk, byte[] newVk, BiPredicate<String, byte[]> ours) {
        Result r = new Result(new ArrayList<>(), new ArrayList<>());
        walk(s, Vault.ROOT, Set.of(Vault.SLOTS, Vault.INFO), stored -> reseal(s, stored, oldVk, newVk, ours, r),
            stored -> r.failed().add(logical(stored)));
        return r;
    }

    /** One file of the vault, by where it's kept. */
    interface Visit {
        void file(String stored) throws IOException;
    }

    /**
     * Each file under {@code dir} but {@code skipped} (the vault's own: its slots and its file), one that fails, or a
     * folder that can't be listed, told to {@code failed} (where it's kept) and the rest goes on. Names from the account
     * are untrusted: only a plain name goes into a path. The vault's own names are folders without a dot (Settings, Texts,
     * machine ids) and files with one, so no service's way of telling them apart is needed.
     */
    static void walk(Storage s, String dir, Set<String> skipped, Visit visit, java.util.function.Consumer<String> failed) {
        List<String> names;
        try {
            names = s.list(dir);
        } catch (IOException e) {
            failed.accept(dir);
            return;
        }
        for (String name : names) {
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) continue;
            String path = dir + "/" + name;
            if (skipped.contains(path)) continue;
            if (name.indexOf('.') < 0) {
                walk(s, path, skipped, visit, failed);
                continue;
            }
            try {
                visit.file(path);
            } catch (IOException | RuntimeException e) {
                failed.accept(path);
            }
        }
    }

    /** Written only when the file is still the one read: another computer may have just sent a newer one. */
    private static void reseal(Storage s, String stored, byte[] oldVk, byte[] newVk, BiPredicate<String, byte[]> ours, Result r)
        throws IOException {
        String logical = logical(stored);
        String stamp = s.stamp(stored);
        byte[] sealed = s.get(stored);
        String aad = OldVault.aad(s, logical);
        if (sealed == null || opens(newVk, aad, sealed)) return;
        byte[] plain;
        try {
            plain = VaultCrypto.open(oldVk, aad, sealed);
        } catch (VaultCrypto.Broken e) {
            r.left().add(logical);
            return;
        }
        if (!ours.test(logical, plain)) {
            r.left().add(logical);
            return;
        }
        if (Objects.equals(stamp, s.stamp(stored))) s.put(stored, VaultCrypto.seal(newVk, aad, plain));
    }

    private static String logical(String stored) {
        return stored.equals(Vault.ROOT) ? CloudAccount.ROOT : CloudAccount.ROOT + "/" + stored.substring(PREFIX.length());
    }

    private static boolean opens(byte[] key, String path, byte[] sealed) {
        try {
            VaultCrypto.open(key, path, sealed);
            return true;
        } catch (VaultCrypto.Broken e) {
            return false;
        }
    }
}

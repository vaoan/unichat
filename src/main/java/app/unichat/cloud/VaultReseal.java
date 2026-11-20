package app.unichat.cloud;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every file the vault keeps, sealed again from an old vault key to a new one: by the computer that changed the key, and
 * by each one as it takes the new key (it may have sent a file with the old one meanwhile). A file that already opens
 * with the new key is left; one that opens with neither is left and named.
 */
final class VaultReseal {
    private static final String PREFIX = Vault.ROOT + "/";

    private VaultReseal() {}

    /** The logical paths of the files that open with neither key. */
    static List<String> run(Storage s, byte[] oldVk, byte[] newVk) throws IOException {
        List<String> left = new ArrayList<>();
        walk(s, Vault.ROOT, oldVk, newVk, left);
        return left;
    }

    /**
     * Names from the account are untrusted: only a plain name goes into a path. The vault's own names are folders without
     * a dot (Ajustes, Textos, machine ids) and files with one, so no service's way of telling them apart is needed.
     */
    private static void walk(Storage s, String dir, byte[] oldVk, byte[] newVk, List<String> left) throws IOException {
        for (String name : s.list(dir)) {
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) continue;
            String path = dir + "/" + name;
            if (path.equals(Vault.SLOTS) || path.equals(Vault.INFO)) continue;
            if (name.indexOf('.') < 0) walk(s, path, oldVk, newVk, left);
            else reseal(s, path, oldVk, newVk, left);
        }
    }

    /** Written only when the file is still the one read: another computer may have just sent a newer one. */
    private static void reseal(Storage s, String stored, byte[] oldVk, byte[] newVk, List<String> left) throws IOException {
        String logical = CloudAccount.ROOT + "/" + stored.substring(PREFIX.length());
        String stamp = s.stamp(stored);
        byte[] sealed = s.get(stored);
        if (sealed == null || opens(newVk, logical, sealed)) return;
        byte[] plain;
        try {
            plain = VaultCrypto.open(oldVk, logical, sealed);
        } catch (VaultCrypto.Broken e) {
            left.add(logical);
            return;
        }
        if (Objects.equals(stamp, s.stamp(stored))) s.put(stored, VaultCrypto.seal(newVk, logical, plain));
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

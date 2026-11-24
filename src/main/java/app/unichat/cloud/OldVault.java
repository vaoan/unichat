package app.unichat.cloud;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * The account as the vault's code sees it while the vault is still under its old names ({@link CloudNames#OLD_VAULT}):
 * every path under {@link Vault#ROOT} reaches its old place, and a folder's names come back new, so {@link Vault},
 * {@link VaultParts}, {@link SealedStorage} and the rest run over it unchanged; a file of the vault was sealed then under
 * its old logical path ({@link #aad}). Any other path is the account's as it is. Until {@link VaultNames} moves the vault,
 * it opens and goes on under these names.
 */
final class OldVault implements Storage {
    private final Storage plain;

    OldVault(Storage plain) {
        this.plain = plain;
    }

    /** What a file of the vault at {@code logical} is sealed under, read through {@code s}: its old path under the old names. */
    static String aad(Storage s, String logical) {
        return s instanceof OldVault ? CloudNames.old(logical) : logical;
    }

    private static String at(String path) {
        return CloudNames.oldStored(path);
    }

    @Override public void check() throws IOException { plain.check(); }
    @Override public boolean exists(String path) throws IOException { return plain.exists(at(path)); }
    @Override public @Nullable String stamp(String path) throws IOException { return plain.stamp(at(path)); }
    @Override public void mkdirs(String dir) throws IOException { plain.mkdirs(at(dir)); }
    @Override public void delete(String path) throws IOException { plain.delete(at(path)); }
    @Override public void move(String from, String to) throws IOException { plain.move(at(from), at(to)); }
    @Override public void put(String path, Path file) throws IOException { plain.put(at(path), file); }
    @Override public void put(String path, Path file, @Nullable LongConsumer sent) throws IOException { plain.put(at(path), file, sent); }
    @Override public void put(String path, byte[] data) throws IOException { plain.put(at(path), data); }
    @Override public byte @Nullable [] get(String path) throws IOException { return plain.get(at(path)); }
    @Override public boolean get(String path, Path target) throws IOException { return plain.get(at(path), target); }
    @Override public @Nullable RemoteFile info(String path) throws IOException { return plain.info(at(path)); }

    /** A folder of the vault's names as they are now: "llaves" says "keys", "Ajustes" says "Settings". */
    @Override
    public List<String> list(String dir) throws IOException {
        String old = at(dir);
        List<String> names = plain.list(old);
        if (old.equals(dir)) return names;
        List<String> out = new ArrayList<>();
        for (String name : names) {
            String now = CloudNames.newStored(old + "/" + name);
            out.add(now.substring(now.lastIndexOf('/') + 1));
        }
        return out;
    }
}

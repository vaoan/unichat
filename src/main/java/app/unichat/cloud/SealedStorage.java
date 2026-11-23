package app.unichat.cloud;

import app.unichat.support.I18n;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A {@link Storage} that seals what goes in and opens what comes out, so the sync classes run over the vault unchanged.
 * A logical path {@code Unichat/<rest>} is kept at {@link Vault#ROOT}{@code /<rest>}, sealed with the logical path as its
 * associated data (a file copied to another name opens nothing; under the vault's old names, the old logical path:
 * {@link OldVault}). Only for what the vault keeps: media never goes through
 * it, and files are read whole. {@code vk} gives a fresh copy of the key on every call, cleared after each use and never kept.
 */
final class SealedStorage implements Storage {
    private static final String PREFIX = CloudAccount.ROOT + "/";

    /** A file of the vault that doesn't open with this key: damaged, or sealed with another one. */
    static final class Damaged extends IOException {
        Damaged() {
            super(I18n.t("cloud.error.vaultDamaged"));
        }
    }
    private final Storage plain;
    private final Supplier<byte[]> vk;
    private final Predicate<String> absentIfDamaged;
    private final Consumer<String> written;

    SealedStorage(Storage plain, Supplier<byte[]> vk) {
        this(plain, vk, path -> false, path -> { });
    }

    /**
     * @param absentIfDamaged whether a file at this logical path that doesn't open reads as none (asked only then)
     * @param written told each logical path once its file went up
     */
    SealedStorage(Storage plain, Supplier<byte[]> vk, Predicate<String> absentIfDamaged, Consumer<String> written) {
        this.plain = plain;
        this.vk = vk;
        this.absentIfDamaged = absentIfDamaged;
        this.written = written;
    }

    @Override
    public void check() throws IOException {
        plain.check();
    }

    @Override
    public boolean exists(String path) throws IOException {
        return plain.exists(stored(path));
    }

    /** Changes with every seal (new nonce): callers decide on the opened content, not on this. */
    @Override
    public String stamp(String path) throws IOException {
        return plain.stamp(stored(path));
    }

    @Override
    public void mkdirs(String dir) throws IOException {
        plain.mkdirs(stored(dir));
    }

    @Override
    public List<String> list(String dir) throws IOException {
        return plain.list(stored(dir));
    }

    @Override
    public void delete(String path) throws IOException {
        plain.delete(stored(path));
    }

    /** Never: a file's logical path is sealed with it, so one moved opens nothing ({@link VaultReseal} seals it again instead). */
    @Override
    public void move(String from, String to) {
        throw new IllegalArgumentException("A file of the vault is sealed with its path: it can't be moved, only sealed again");
    }

    @Override
    public void put(String path, Path file) throws IOException {
        put(path, file, null);
    }

    @Override
    public void put(String path, Path file, LongConsumer sent) throws IOException {
        byte[] sealed = seal(path, Files.readAllBytes(file));
        plain.put(stored(path), sealed);
        written.accept(path);
        if (sent != null) sent.accept(sealed.length);
    }

    @Override
    public void put(String path, byte[] data) throws IOException {
        plain.put(stored(path), seal(path, data));
        written.accept(path);
    }

    @Override
    public byte[] get(String path) throws IOException {
        byte[] sealed = plain.get(stored(path));
        if (sealed == null) return null;
        try {
            return open(path, sealed);
        } catch (Damaged e) {
            if (absentIfDamaged.test(path)) return null;
            throw e;
        }
    }

    @Override
    public boolean get(String path, Path target) throws IOException {
        byte[] data = get(path);
        if (data == null) return false;
        Storage.save(new ByteArrayInputStream(data), target);
        return true;
    }

    @Override
    public RemoteFile info(String path) throws IOException {
        return exists(path) ? new RemoteFile(-1, null, null) : null;
    }

    private byte[] seal(String path, byte[] data) {
        stored(path);
        byte[] key = vk.get();
        try {
            return VaultCrypto.seal(key, OldVault.aad(plain, path), data);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private byte[] open(String path, byte[] sealed) throws Damaged {
        byte[] key = vk.get();
        try {
            return VaultCrypto.open(key, OldVault.aad(plain, path), sealed);
        } catch (VaultCrypto.Broken e) {
            throw new Damaged();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Where the logical path is kept. Only a named file or folder under {@code Unichat/} that is neither media nor the vault's
     * own folder: an empty, "." or ".." part would reach other places once a server resolves it.
     */
    private static String stored(String path) {
        boolean ok = path.startsWith(PREFIX) && !path.equals(Vault.ROOT) && !path.startsWith(Vault.ROOT + "/")
            && !path.equals(CloudAccount.MEDIA) && !path.startsWith(CloudAccount.MEDIA + "/");
        String rest = ok ? path.substring(PREFIX.length()) : "";
        for (String part : rest.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) ok = false;
        }
        if (!ok) throw new IllegalArgumentException("The vault keeps only named files under " + PREFIX + ", not media: " + path);
        return Vault.ROOT + "/" + rest;
    }
}

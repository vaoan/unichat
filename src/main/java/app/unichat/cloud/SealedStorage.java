package app.unichat.cloud;

import app.unichat.support.I18n;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * A {@link Storage} that seals what goes in and opens what comes out, so the sync classes run over the vault unchanged.
 * A logical path {@code Unichat/<rest>} is kept at {@link Vault#ROOT}{@code /<rest>}, sealed with the logical path as its
 * associated data (a file copied to another name opens nothing). Only for what the vault keeps: media never goes through
 * it, and files are read whole. The key comes from {@code vk} on every call and is never kept.
 */
final class SealedStorage implements Storage {
    private static final String PREFIX = CloudAccount.ROOT + "/";
    private final Storage plain;
    private final Supplier<byte[]> vk;

    SealedStorage(Storage plain, Supplier<byte[]> vk) {
        this.plain = plain;
        this.vk = vk;
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

    @Override
    public void put(String path, Path file) throws IOException {
        put(path, Files.readAllBytes(file));
    }

    @Override
    public void put(String path, Path file, LongConsumer sent) throws IOException {
        byte[] sealed = VaultCrypto.seal(vk.get(), checked(path), Files.readAllBytes(file));
        plain.put(stored(path), sealed);
        if (sent != null) sent.accept(sealed.length);
    }

    @Override
    public void put(String path, byte[] data) throws IOException {
        plain.put(stored(path), VaultCrypto.seal(vk.get(), checked(path), data));
    }

    @Override
    public byte[] get(String path) throws IOException {
        byte[] sealed = plain.get(stored(path));
        return sealed == null ? null : open(path, sealed);
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

    private byte[] open(String path, byte[] sealed) throws IOException {
        try {
            return VaultCrypto.open(vk.get(), checked(path), sealed);
        } catch (VaultCrypto.Broken e) {
            throw new IOException(I18n.t("cloud.error.vaultDamaged"));
        }
    }

    private static String checked(String path) {
        stored(path);
        return path;
    }

    /** Where the logical path is kept; anything outside what the vault keeps is refused. */
    private static String stored(String path) {
        boolean ok = path.startsWith(PREFIX) && !path.startsWith(Vault.ROOT) && !path.equals(CloudAccount.MEDIA)
            && !path.startsWith(CloudAccount.MEDIA + "/");
        if (!ok) throw new IllegalArgumentException("The vault keeps only what is under " + PREFIX + ", not media");
        return Vault.ROOT + "/" + path.substring(PREFIX.length());
    }
}

package app.unichat.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The person's storage, however it's reached: WebDAV ({@link WebDav}: Icedrive, Nextcloud…) or pCloud's own API
 * ({@link PCloudApi}, which signs in through the browser and so works with two-step verification). Paths are relative
 * to the account's root, "/"-separated. Failures say why in the person's words.
 */
public interface Storage {
    /** Whether it answers and takes this login. */
    void check() throws IOException;

    boolean exists(String path) throws IOException;

    /** What tells this version of {@code path} from the next, without downloading it; null when there's none. */
    String stamp(String path) throws IOException;

    /** Makes {@code dir} and the folders above it that are missing. */
    void mkdirs(String dir) throws IOException;

    void put(String path, Path file) throws IOException;

    /** The same, telling {@code sent} the running total of bytes gone out (null: nobody listens). */
    default void put(String path, Path file, java.util.function.LongConsumer sent) throws IOException {
        put(path, file);
    }

    void put(String path, byte[] data) throws IOException;

    /** The file at {@code path}, or null when there's none: for small files (the settings); media goes {@link #get(String, Path)}. */
    byte[] get(String path) throws IOException;

    /**
     * The file at {@code path} into {@code target}, as it arrives, never whole in memory (a video can be gigabytes): through
     * a hidden ".part" beside it, which takes {@code target}'s name only once whole. False, and {@code target} untouched,
     * when there's none.
     */
    boolean get(String path, Path target) throws IOException;

    /** {@code in} into {@code target} as {@link #get(String, Path)} says: through a hidden ".part", renamed once whole. */
    static void save(InputStream in, Path target) throws IOException {
        Path part = target.resolveSibling("." + target.getFileName() + ".part");
        try (in) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * What the account knows of a file there: its size (-1 unknown) and, where the service gives one, its checksum
     * ({@link Checksums} kinds). Null when there's none.
     */
    record RemoteFile(long size, String kind, String hash) {}

    default RemoteFile info(String path) throws IOException {
        return exists(path) ? new RemoteFile(-1, null, null) : null;
    }
}

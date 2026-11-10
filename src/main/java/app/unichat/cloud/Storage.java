package app.unichat.cloud;

import java.io.IOException;
import java.nio.file.Path;

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

    void put(String path, byte[] data) throws IOException;

    /** The file at {@code path}, or null when there's none. */
    byte[] get(String path) throws IOException;
}

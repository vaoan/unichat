package app.unichat.cloud;

import app.unichat.support.SafeFile;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
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
    @Nullable String stamp(String path) throws IOException;

    /** Makes {@code dir} and the folders above it that are missing. */
    void mkdirs(String dir) throws IOException;

    /** The names of what's right in {@code dir}, files and folders; empty when there's no such folder. */
    java.util.List<String> list(String dir) throws IOException;

    /** Deletes the file at {@code path}, or the folder with all in it; nothing there is fine. */
    void delete(String path) throws IOException;

    /**
     * What every {@link #delete} asks first: a path with no name in it (the account's root) or a "." or ".." part could reach
     * more than was meant, so it is refused before anything is sent.
     */
    static void deletable(String path) throws IOException {
        boolean named = false;
        for (String part : path.split("/")) {
            if (part.equals(".") || part.equals("..")) throw new IOException(app.unichat.support.I18n.t("cloud.error.badPath"));
            named |= !part.isEmpty();
        }
        if (!named) throw new IOException(app.unichat.support.I18n.t("cloud.error.badPath"));
    }

    /**
     * Moves the file or folder at {@code from} (with all in it) to {@code to}, on the server: nothing comes down or goes up
     * again. The folder above {@code to} must be there (callers make it). Never over something already at {@code to}, and
     * nothing at {@code from}: both fail, leaving everything as it was. Both paths are checked as {@link #deletable}.
     */
    void move(String from, String to) throws IOException;

    /** Whether a name from the account may become one part of a path here: not empty, no separator, not "." nor "..". */
    static boolean plainName(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.equals(".") && !name.equals("..");
    }

    void put(String path, Path file) throws IOException;

    /** The same, telling {@code sent} the running total of bytes gone out (null: nobody listens). */
    default void put(String path, Path file, java.util.function.@Nullable LongConsumer sent) throws IOException {
        put(path, file);
    }

    void put(String path, byte[] data) throws IOException;

    /** The file at {@code path}, or null when there's none: for small files (the settings); media goes {@link #get(String, Path)}. */
    byte @Nullable [] get(String path) throws IOException;

    /**
     * The file at {@code path} into {@code target}, as it arrives, never whole in memory (a video can be gigabytes): through
     * a hidden ".part" beside it, which takes {@code target}'s name only once whole. False, and {@code target} untouched,
     * when there's none.
     */
    boolean get(String path, Path target) throws IOException;

    /** {@code in} into {@code target} as {@link #get(String, Path)} says: through a hidden ".part", renamed once whole. */
    static void save(InputStream in, Path target) throws IOException {
        try (in) {
            SafeFile.at(target, ".part").hidden().copy(in);
        }
    }

    /**
     * What the account knows of a file there: its size (-1 unknown) and, where the service gives one, its checksum
     * ({@link Checksums} kinds). Null when there's none.
     */
    record RemoteFile(long size, @Nullable String kind, @Nullable String hash) {}

    default @Nullable RemoteFile info(String path) throws IOException {
        return exists(path) ? new RemoteFile(-1, null, null) : null;
    }
}

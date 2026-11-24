package app.unichat.cloud;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A file's checksum computed here the way a service computes it there, so the two copies can be compared: SHA-1 or
 * SHA-256 (pCloud), or Dropbox's own content hash (SHA-256 of the SHA-256 of each 4 MB block).
 */
final class Checksums {
    static final String SHA1 = "sha1";
    static final String SHA256 = "sha256";
    static final String DROPBOX = "dropbox";
    private static final int DROPBOX_BLOCK = 4 * 1024 * 1024;

    private Checksums() {}

    /** {@code file}'s checksum of {@code kind}, in lowercase hex. */
    static String of(Path file, String kind) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            if (DROPBOX.equals(kind)) {
                MessageDigest all = MessageDigest.getInstance("SHA-256");
                for (byte[] block = in.readNBytes(DROPBOX_BLOCK); block.length > 0; block = in.readNBytes(DROPBOX_BLOCK)) {
                    all.update(MessageDigest.getInstance("SHA-256").digest(block));
                }
                return HexFormat.of().formatHex(all.digest());
            }
            MessageDigest d = MessageDigest.getInstance(SHA1.equals(kind) ? "SHA-1" : "SHA-256"); // lgtm[java/potentially-weak-cryptographic-algorithm] pCloud's API gives a file's SHA-1: it tells a changed file, it guards nothing
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) d.update(buf, 0, n);
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What to do with a saved file and its copy in the account. */
    enum Verdict { SAME, UPLOAD, RESTORE_LOCAL, CONFLICT }

    /**
     * Pure. No copy up there: upload. Same checksum (or, where the service gives none, same size): the same. Otherwise
     * the bigger one wins, because damage usually cuts a file short: this one bigger, upload it again; the account's
     * bigger, bring it back here and never touch it there. Same size, different content: neither is known to be right,
     * so neither is touched.
     */
    @SuppressFBWarnings(value = "IMPROPER_UNICODE", justification = "compares two hex checksums the services give; hex is ASCII, and a wrong answer only means a file is checked again")
    static Verdict decide(long localSize, @Nullable String localHash, Storage.@Nullable RemoteFile remote) {
        if (remote == null) return Verdict.UPLOAD;
        String remoteHash = remote.hash();
        if (remoteHash != null && localHash != null && remoteHash.equalsIgnoreCase(localHash)) return Verdict.SAME;
        if (remote.size() < 0) return Verdict.SAME; // it only says the file is there
        if (localSize > remote.size()) return Verdict.UPLOAD;
        if (localSize < remote.size()) return Verdict.RESTORE_LOCAL;
        return remoteHash == null ? Verdict.SAME : Verdict.CONFLICT;
    }
}

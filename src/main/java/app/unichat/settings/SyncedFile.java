package app.unichat.settings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Safe file replacement: the temp half is on disk before anyone renames it, or a power cut can leave an empty file. */
public final class SyncedFile {
    private SyncedFile() {}

    /** Creates {@code file} anew, so a leftover (or a link) never decides where the bytes go or who can read them. */
    public static void write(Path file, byte[] content, FileAttribute<?>... attributes) throws IOException {
        Files.deleteIfExists(file);
        try (FileChannel out = FileChannel.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes)) {
            ByteBuffer rest = ByteBuffer.wrap(content);
            while (rest.hasRemaining()) out.write(rest);
            out.force(true);
        }
    }

    /**
     * Replaces {@code target} whole, through a temp file beside it: a failure or a crash leaves the old one as it was.
     * @param ownerOnly it holds secrets: nobody else can read it, not even for a moment. On Windows the person's own folder already sees to that.
     */
    public static void replace(Path target, byte[] content, boolean ownerOnly) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            if (ownerOnly && target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                write(tmp, content, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                write(tmp, content);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException stays) {
                e.addSuppressed(stays);
            }
            throw e;
        }
    }
}

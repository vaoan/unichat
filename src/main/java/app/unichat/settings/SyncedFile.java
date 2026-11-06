package app.unichat.settings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;

/** The temp half of a safe file replacement: on disk before anyone renames it, or a power cut can leave an empty file. */
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
}

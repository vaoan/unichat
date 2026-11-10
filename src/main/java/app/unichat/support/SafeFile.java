package app.unichat.support;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes a file whole or not at all: into a temp file beside it, on the disk (fsync) before it takes the file's name,
 * so a failure, a crash or a power cut leaves the old file as it was. A failure also removes the temp file. The temp is
 * always created anew, so a leftover (or a link in its place) never decides where the bytes go or who can read them.
 * The one place that renames a temp file into place: use it instead of another.
 */
public final class SafeFile {
    /** What goes into the file. The stream may be closed or not: the file is synced and closed here. */
    @FunctionalInterface
    public interface Content { void writeTo(OutputStream out) throws IOException; }

    /** A last look at the whole temp file before it takes the name (a checksum, the executable bit); throwing keeps the old file. */
    @FunctionalInterface
    public interface Check { void accept(Path part) throws IOException; }

    private final Path target;
    private final String suffix;
    private final boolean hidden, ownerOnly;
    private final Check beforeMove;

    private SafeFile(Path target, String suffix, boolean hidden, boolean ownerOnly, Check beforeMove) {
        this.target = target;
        this.suffix = suffix;
        this.hidden = hidden;
        this.ownerOnly = ownerOnly;
        this.beforeMove = beforeMove;
    }

    /** Through {@code <name>.tmp}. */
    public static SafeFile at(Path target) { return at(target, ".tmp"); }

    /** Through {@code <name><suffix>}: two writers of one file each keep their own suffix, or they'd take each other's temp. */
    public static SafeFile at(Path target, String suffix) { return new SafeFile(target, suffix, false, false, part -> { }); }

    /** The temp name starts with a dot: hidden, and skipped by whoever lists the folder (the folders autosave fills). */
    public SafeFile hidden() { return new SafeFile(target, suffix, true, ownerOnly, beforeMove); }

    /** It holds secrets: nobody else can read it, not even for a moment. On Windows the person's own folder already sees to that. */
    public SafeFile ownerOnly() { return new SafeFile(target, suffix, hidden, true, beforeMove); }

    public SafeFile beforeMove(Check check) { return new SafeFile(target, suffix, hidden, ownerOnly, check); }

    /** The temp file beside the target. */
    public Path part() { return target.resolveSibling((hidden ? "." : "") + target.getFileName() + suffix); }

    /** {@code content} through {@code <name>.tmp}, owner-only when it holds secrets. */
    public static void replace(Path target, byte[] content, boolean ownerOnly) throws IOException {
        SafeFile file = at(target);
        (ownerOnly ? file.ownerOnly() : file).write(content);
    }

    public void write(byte[] content) throws IOException { write(out -> out.write(content)); }

    /** As it arrives, never whole in memory; {@code in} is left open (an archive's next entry may follow). */
    public void copy(InputStream in) throws IOException { write(in::transferTo); }

    public void write(Content content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path part = part();
        try {
            Files.deleteIfExists(part);
            try (FileChannel channel = FileChannel.open(part, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes())) {
                OutputStream out = new KeptOpen(Channels.newOutputStream(channel));
                content.writeTo(out);
                out.flush();
                channel.force(true);
            }
            beforeMove.accept(part);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); // lesson-ok: atomic-move
        } catch (IOException | RuntimeException | Error e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException stays) {
                e.addSuppressed(stays);
            }
            throw e;
        }
    }

    private FileAttribute<?>[] attributes() {
        if (!ownerOnly || !target.getFileSystem().supportedFileAttributeViews().contains("posix")) return new FileAttribute<?>[0];
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))};
    }

    /** Closing only flushes: Jackson closes what it writes to, and the channel must stay open to be synced. */
    private static final class KeptOpen extends BufferedOutputStream {
        KeptOpen(OutputStream out) { super(out, 1 << 16); }

        @Override
        public void close() throws IOException { flush(); }
    }
}

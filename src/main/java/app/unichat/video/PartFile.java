package app.unichat.video;

import app.unichat.support.Os;
import app.unichat.support.Processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * A file another program makes: written under a hidden ".part" name beside where it goes and moved into place once
 * whole, so nothing (the media cache, autosave, the cloud) ever takes a half-written one. Each way to make it is tried
 * in turn until one works.
 */
final class PartFile {
    /** macOS's own picture tool: always there on a Mac, nothing to download. */
    static final Path SIPS = Path.of("/usr/bin/sips");

    /** One way to make it: the command that writes {@code into}; null when this way isn't there (no ffmpeg). */
    interface Way {
        List<String> command(Path into) throws IOException, InterruptedException;
    }

    /** An ffmpeg command writing {@code into}. */
    interface WithFfmpeg {
        List<String> command(Path ffmpeg, Path into) throws IOException;
    }

    private PartFile() {}

    /**
     * Tries each way in turn, each given {@code timeoutSeconds}; the first that ends well with a file that isn't empty
     * is moved to {@code out}, which is given back. Null when none did.
     */
    static Path firstOf(Path out, long timeoutSeconds, List<Way> ways) throws IOException, InterruptedException {
        Path part = part(out);
        try {
            for (Way way : ways) {
                List<String> command = way.command(part);
                if (command != null && Processes.quiet(command, null, timeoutSeconds).ok() && Files.isRegularFile(part)
                        && Files.size(part) > 0) {
                    Files.move(part, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return out;
                }
            }
            return null;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * sips on a Mac; ffmpeg from {@code source} only when sips isn't there or failed, since getting it may mean
     * downloading ~28 MB.
     */
    static List<Way> sipsThenFfmpeg(Way sips, Ffmpeg.Source source, WithFfmpeg ffmpeg) {
        return List.of(
                into -> Os.mac() && Files.isExecutable(SIPS) ? sips.command(into) : null,
                into -> {
                    Path found = source.get();
                    return found == null ? null : ffmpeg.command(found, into);
                });
    }

    /** Hidden and with ".part" (what the cache, autosave and the cloud leave alone), the extension kept: ffmpeg goes by it. */
    static Path part(Path out) {
        String name = out.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return out.resolveSibling("." + (dot > 0 ? name.substring(0, dot) + ".part" + name.substring(dot) : name + ".part"));
    }
}

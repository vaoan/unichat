package app.unichat.video;

import app.unichat.support.Archives;
import app.unichat.support.Downloads;
import app.unichat.support.I18n;
import app.unichat.support.Processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * A program downloaded on first use, pinned by its checksum.
 * @param entry the end of the executable's path inside the zip it comes in; null: the download is the executable
 */
record PinnedTool(String url, long size, String sha256, String entry) {
    /** A first run unpacks itself (yt-dlp's) or is checked by the system: slow once, never this slow. */
    private static final long CHECK_SECONDS = 60;

    /**
     * Downloads it (and takes it out of its zip) into {@code target}, which only stays if it answers {@code checkArg}
     * ("--version"). {@code what}: the download as the person reads it.
     */
    Path install(Path target, String what, String checkArg, DoubleConsumer progress) throws IOException, InterruptedException {
        if (entry == null) {
            Downloads.fetch(url, size, sha256, target, what, progress);
        } else {
            Path zip = target.resolveSibling(target.getFileName() + "-download.zip");
            try {
                Downloads.fetch(url, size, sha256, zip, what, progress);
                if (!Archives.extractOne(zip, entry, target)) throw new IOException(I18n.t("video.noProgram"));
            } finally {
                Files.deleteIfExists(zip);
            }
        }
        target.toFile().setExecutable(true);
        if (!Processes.quiet(List.of(target.toString(), checkArg), null, CHECK_SECONDS).ok()) {
            Files.deleteIfExists(target);
            throw new IOException(I18n.t("video.badConverter"));
        }
        return target;
    }
}

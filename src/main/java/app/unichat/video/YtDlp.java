package app.unichat.video;

import app.unichat.settings.Settings;
import app.unichat.support.Downloads;
import app.unichat.support.I18n;
import app.unichat.support.Os;
import app.unichat.support.Processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * yt-dlp, which downloads the video behind a link (YouTube, Instagram, TikTok, X and hundreds more): the pinned
 * standalone build, downloaded on first use into {@code <data>/tools/yt-dlp/} and kept only if it matches its checksum
 * and runs. Running it (sites, cookies, progress) is InstaTray's engine's ({@code instatray.core}, autosave/LinkSaver).
 */
public final class YtDlp {
    record Build(String url, long size, String sha256) {}

    private static final String RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/";
    private static final Build MAC = new Build(RELEASE + "yt-dlp_macos", 37_466_304L,
            "0f922c15ccc0b83eefa1c8bc9104e434318c4bf16e408e1ee93b7745a6efe0aa");
    private static final Build WINDOWS_AMD64 = new Build(RELEASE + "yt-dlp.exe", 23_575_744L,
            "f5389d3698ecde4ef06f4e6b1ce0294fe9ac1396228c8990b8d3416d6d177a7d");
    private static final String EXE = Os.windows() ? "yt-dlp.exe" : "yt-dlp";
    /** A long video takes a while; one stuck that long isn't coming. */

    private YtDlp() {}

    /** Whether there's a build for this computer. */
    public static boolean canInstall() { return build() != null; }

    /** yt-dlp, downloading it when missing. Blocking; one at a time. Null when there's none for this computer. */
    public static synchronized Path whenNeeded(DoubleConsumer progress) throws IOException, InterruptedException {
        Path target = Settings.get().dir().resolve("tools").resolve("yt-dlp").resolve(EXE);
        if (Files.isExecutable(target)) return target;
        Build b = build();
        return b == null ? null : install(b, target, progress);
    }

    /** Downloads {@code b} into {@code target}; it only stays if it answers {@code --version}. */
    static Path install(Build b, Path target, DoubleConsumer progress) throws IOException, InterruptedException {
        Downloads.fetch(b.url(), b.size(), b.sha256(), target, I18n.t("download.what.linkVideos"), progress);
        target.toFile().setExecutable(true);
        if (!Processes.quiet(List.of(target.toString(), "--version"), null, 60).ok()) {
            Files.deleteIfExists(target);
            throw new IOException(I18n.t("video.badConverter"));
        }
        return target;
    }

    private static Build build() {
        String arch = System.getProperty("os.arch", "");
        if (Os.mac()) return MAC; // universal: Apple chips and Intel
        if (Os.windows() && (arch.equals("amd64") || arch.equals("x86_64"))) return WINDOWS_AMD64;
        return null;
    }
}

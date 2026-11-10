package app.unichat.video;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.support.Os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.DoubleConsumer;

/**
 * yt-dlp, which downloads the video behind a link (YouTube, Instagram, TikTok, X and hundreds more): the pinned
 * standalone build, downloaded on first use into {@code <data>/tools/yt-dlp/} and kept only if it matches its checksum
 * and runs. Running it (sites, cookies, progress) is InstaTray's engine's ({@code instatray.core}, autosave/LinkSaver).
 */
public final class YtDlp {
    private static final String RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/";
    /** Universal: Apple chips and Intel. */
    private static final PinnedTool MAC = new PinnedTool(RELEASE + "yt-dlp_macos", 37_466_304L,
            "0f922c15ccc0b83eefa1c8bc9104e434318c4bf16e408e1ee93b7745a6efe0aa", null);
    private static final PinnedTool WINDOWS_AMD64 = new PinnedTool(RELEASE + "yt-dlp.exe", 23_575_744L,
            "f5389d3698ecde4ef06f4e6b1ce0294fe9ac1396228c8990b8d3416d6d177a7d", null);
    private static final String EXE = Os.windows() ? "yt-dlp.exe" : "yt-dlp";

    private YtDlp() {}

    /** yt-dlp, downloading it when missing. Blocking; one at a time. Null when there's none for this computer. */
    public static synchronized Path whenNeeded(DoubleConsumer progress) throws IOException, InterruptedException {
        Path target = Settings.get().dir().resolve("tools").resolve("yt-dlp").resolve(EXE);
        if (Files.isExecutable(target)) return target;
        PinnedTool b = Os.pick(MAC, MAC, WINDOWS_AMD64);
        return b == null ? null : b.install(target, I18n.t("download.what.linkVideos"), "--version", progress);
    }
}

package app.unichat.video;

import app.unichat.settings.Settings;
import app.unichat.support.BundledTools;
import app.unichat.support.I18n;
import app.unichat.support.Os;
import app.unichat.support.Processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * ffmpeg, too big to ship: the person's own if there is one, otherwise a pinned build downloaded on first use into
 * {@code <data>/tools/ffmpeg/} (Ajustes → Redes can turn that off) and kept only if it matches its checksum.
 */
public final class Ffmpeg {
    private static final PinnedTool MAC_ARM64 = new PinnedTool("https://ffmpeg.martin-riedl.de/download/macos/arm64/1789931890_9.0.2/ffmpeg.zip",
            28_395_699L, "c8ed4c4e6978a03c485edbfe4e0a5dc2380f8a30bba5150531b31b094492d924", "ffmpeg");
    private static final PinnedTool WINDOWS_AMD64 = new PinnedTool("https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-essentials_build.zip",
            114_768_076L, "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba", "/bin/ffmpeg.exe");
    /** A Mac app opened from Finder doesn't get the shell's PATH. */
    private static final List<String> USUAL_FOLDERS = List.of("/opt/homebrew/bin", "/usr/local/bin");
    private static final String EXE = Os.windows() ? "ffmpeg.exe" : "ffmpeg";
    private static final String AUTO_KEY = "video.ffmpegAuto";

    /** Where an ffmpeg comes from when one is needed: null when there's none to use. */
    public interface Source {
        Path get() throws IOException, InterruptedException;
    }

    /** The person's own, or the pinned one downloaded if the setting allows. */
    public static final Source WHEN_NEEDED = () -> whenNeeded(p -> { });

    private Ffmpeg() {}

    public static boolean autoInstall() { return Settings.get().getBool(AUTO_KEY, true); }

    public static void setAutoInstall(boolean on) { Settings.get().setBool(AUTO_KEY, on); }

    /** The ffmpeg to use now, or null. {@code -Dunichat.ffmpeg} is the only one considered when set. */
    public static Path find() {
        Path own = BundledTools.find("unichat.ffmpeg", null, "ffmpeg");
        if (own != null || System.getProperty("unichat.ffmpeg") != null) return own;
        Path installed = installedPath();
        if (Files.isExecutable(installed)) return installed;
        for (String d : USUAL_FOLDERS) {
            Path f = Path.of(d, EXE);
            if (Files.isExecutable(f)) return f;
        }
        return null;
    }

    /**
     * The input options for a file from other people: only it may be read. ffmpeg tells the format by the content, and
     * an HLS playlist, a concat list or a DASH manifest names other files, which {@code -protocol_whitelist file} still
     * lets it read (a "video" could carry ~/.ssh/id_rsa into what is saved and uploaded): those are refused.
     */
    public static List<String> input(Path file) throws IOException {
        byte[] head;
        try (var in = java.nio.file.Files.newInputStream(file)) {
            head = in.readNBytes(64);
        }
        String start = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1).replace("\u00EF\u00BB\u00BF", "").stripLeading();
        for (String list : LISTS) {
            if (start.regionMatches(true, 0, list, 0, list.length())) throw new IOException(I18n.t("video.notAVideo"));
        }
        return List.of("-protocol_whitelist", "file", "-i", file.toString()); // lesson-ok: ffmpeg-input
    }

    /** How the formats that name other files begin: HLS, ffmpeg's concat list, a DASH manifest (XML). */
    private static final List<String> LISTS = List.of("#EXTM3U", "ffconcat", "<?xml", "<MPD");

    /**
     * Runs one ffmpeg command, giving up after {@code timeoutSeconds}; whether it worked.
     * @param folder where it runs; null: wherever Unichat does
     */
    public static boolean run(List<String> command, long timeoutSeconds, Path folder) throws IOException, InterruptedException {
        return Processes.quiet(command, folder, timeoutSeconds).ok();
    }

    /** Whether there's a build to download for this computer. */
    public static boolean canInstall() { return build() != null; }

    /** Bytes that installing would download. */
    public static long downloadSize() {
        PinnedTool b = build();
        return b == null ? 0 : b.size();
    }

    /** ffmpeg, downloading it when missing and allowed by the setting; null when there's none to use. Blocking. */
    public static Path whenNeeded(DoubleConsumer progress) throws IOException, InterruptedException {
        Path found = find();
        return found != null || !autoInstall() ? found : install(progress);
    }

    /** Downloads the pinned build (the "Instalar" button). Blocking; one at a time. Null when none exists for this computer. */
    public static synchronized Path install(DoubleConsumer progress) throws IOException, InterruptedException {
        Path found = find();
        if (found != null) return found;
        PinnedTool b = build();
        return b == null ? null : install(b, installedPath(), progress);
    }

    /** Downloads, extracts and tries {@code b}; {@code target} only stays if it runs. */
    static Path install(PinnedTool b, Path target, DoubleConsumer progress) throws IOException, InterruptedException {
        return b.install(target, I18n.t("download.what.videoConverter"), "-version", progress);
    }

    private static PinnedTool build() {
        return Os.pick(MAC_ARM64, null, WINDOWS_AMD64);
    }

    /** Whether {@code found} is the one Unichat downloaded (rather than the person's own). */
    public static boolean downloadedByUnichat(Path found) {
        return found != null && found.equals(installedPath());
    }

    private static Path installedPath() {
        return Settings.get().dir().resolve("tools").resolve("ffmpeg").resolve(EXE);
    }
}

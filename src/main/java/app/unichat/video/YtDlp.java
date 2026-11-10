package app.unichat.video;

import app.unichat.settings.Settings;
import app.unichat.support.Downloads;
import app.unichat.support.I18n;
import app.unichat.support.Os;
import app.unichat.support.Processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * yt-dlp, which downloads the video behind a link (YouTube, Instagram, TikTok, X and hundreds more): the pinned
 * standalone build, downloaded on first use into {@code <data>/tools/yt-dlp/} and kept only if it matches its checksum
 * and runs. It uses Unichat's ffmpeg ({@link Ffmpeg}) to join video and audio.
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
    private static final long DOWNLOAD_SECONDS = 2 * 60 * 60;

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

    /**
     * The command that saves the video at {@code url} into {@code dir}, named after its title. One video, never the
     * playlist it's in; at most 1080p so a long one doesn't take gigabytes; nothing outside {@code dir}.
     */
    static List<String> command(Path ytDlp, Path ffmpeg, Path dir, String url, String tag) {
        List<String> c = new ArrayList<>(List.of(ytDlp.toString(), "--no-playlist", "--newline", "--no-mtime",
                "--windows-filenames", "--no-overwrites", "-f", "bv*[height<=1080]+ba/b[height<=1080]/b",
                // H.264 and AAC first, then the size: Instagram offers VP9 too, which QuickTime and Windows' player don't open.
                "-S", "vcodec:h264,acodec:aac,res:1080", "--merge-output-format", "mp4",
                "-P", dir.toString(), "-o", "%(title).150B [%(id)s]" + (tag == null ? "" : " [" + tag + "]") + ".%(ext)s"));
        if (ffmpeg != null) c.addAll(List.of("--ffmpeg-location", ffmpeg.toString()));
        c.addAll(List.of("--", url));
        return c;
    }

    private static final java.util.regex.Pattern PERCENT = java.util.regex.Pattern.compile("^\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%");

    /** How far a "[download]  45.3% of 10MiB" line says it got (0 to 1); -1 for any other line. */
    static double percent(String line) {
        java.util.regex.Matcher m = PERCENT.matcher(line.strip());
        return m.find() ? Math.min(1, Double.parseDouble(m.group(1)) / 100) : -1;
    }

    /**
     * Saves the video behind {@code url} into {@code dir}. Blocking. Null when it's saved; otherwise why not, in
     * yt-dlp's own words ("Video unavailable", "Unsupported URL"…), or that it took too long.
     */
    public static String download(Path ytDlp, Path ffmpeg, Path dir, String url, String tag) throws IOException, InterruptedException {
        return download(ytDlp, ffmpeg, dir, url, tag, null);
    }

    /** The same, telling {@code progress} how far it got (0 to 1), from yt-dlp's own "[download]  45.3%" lines. */
    public static String download(Path ytDlp, Path ffmpeg, Path dir, String url, String tag, java.util.function.DoubleConsumer progress)
            throws IOException, InterruptedException {
        Files.createDirectories(dir);
        java.util.Set<Path> before = listed(dir);
        int[] told = {-1};
        Processes.Result r = Processes.capture(new ProcessBuilder(command(ytDlp, ffmpeg, dir, url, tag)).redirectErrorStream(true),
                DOWNLOAD_SECONDS, progress == null ? null : line -> {
                    double done = percent(line);
                    if (done >= 0 && (int) (done * 100) != told[0]) {
                        told[0] = (int) (done * 100);
                        progress.accept(done);
                    }
                });
        if (r.ok()) {
            // Asking for H.264 is only asking: whatever came that a Mac or Windows doesn't play becomes H.264 here.
            if (ffmpeg != null) for (Path got : listed(dir)) if (!before.contains(got)) VideoTranscoder.makePlayable(ffmpeg, got);
            return null;
        }
        if (!r.finished()) return I18n.t("autosave.error.tooLong");
        return reason(r.out());
    }

    /** The whole files in {@code dir} (not hidden, not half-written). */
    private static java.util.Set<Path> listed(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return Files.isRegularFile(p) && !n.startsWith(".") && !n.endsWith(".part") && !n.endsWith(".ytdl");
            }).collect(java.util.stream.Collectors.toSet());
        }
    }

    /** yt-dlp's last "ERROR:" line, without its "[site] id:" prefix; the last line when there's none. */
    static String reason(String out) {
        String last = "";
        for (String line : out.strip().split("\\R")) {
            if (line.startsWith("ERROR:")) last = line.substring("ERROR:".length()).strip();
        }
        if (last.isEmpty()) {
            String[] lines = out.strip().split("\\R");
            last = lines.length == 0 ? "" : lines[lines.length - 1].strip();
        }
        return last.replaceFirst("^\\[[^]]+] [^:]+: ", "");
    }

    private static Build build() {
        String arch = System.getProperty("os.arch", "");
        if (Os.mac()) return MAC; // universal: Apple chips and Intel
        if (Os.windows() && (arch.equals("amd64") || arch.equals("x86_64"))) return WINDOWS_AMD64;
        return null;
    }
}

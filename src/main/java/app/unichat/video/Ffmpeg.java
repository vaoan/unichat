package app.unichat.video;

import app.unichat.settings.Settings;
import app.unichat.support.BundledTools;
import app.unichat.support.Downloads;
import app.unichat.support.Os;
import app.unichat.support.Processes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ffmpeg, too big to ship: the person's own if there is one, otherwise a pinned build downloaded on first use into
 * {@code <data>/tools/ffmpeg/} (Ajustes → Redes can turn that off) and kept only if it matches its checksum.
 */
public final class Ffmpeg {
    /** A build to install; {@code entry}: the end of the executable's path inside the zip. */
    record Build(String url, long size, String sha256, String entry) {}

    private static final Build MAC_ARM64 = new Build("https://ffmpeg.martin-riedl.de/download/macos/arm64/1789931890_9.0.2/ffmpeg.zip",
            28_395_699L, "c8ed4c4e6978a03c485edbfe4e0a5dc2380f8a30bba5150531b31b094492d924", "ffmpeg");
    private static final Build WINDOWS_AMD64 = new Build("https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-essentials_build.zip",
            114_768_076L, "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba", "/bin/ffmpeg.exe");
    /** A Mac app opened from Finder doesn't get the shell's PATH. */
    private static final List<String> USUAL_FOLDERS = List.of("/opt/homebrew/bin", "/usr/local/bin");
    private static final String EXE = Os.windows() ? "ffmpeg.exe" : "ffmpeg";
    private static final String AUTO_KEY = "video.ffmpegAuto";

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

    /** Runs one ffmpeg command, giving up after {@code timeoutSeconds}; whether it worked. */
    static boolean run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
        return run(command, timeoutSeconds, null);
    }

    /** @param folder where it runs; null: wherever Unichat does */
    public static boolean run(List<String> command, long timeoutSeconds, Path folder) throws IOException, InterruptedException {
        return Processes.quiet(command, folder, timeoutSeconds).ok();
    }

    /** Whether there's a build to download for this computer. */
    public static boolean canInstall() { return build() != null; }

    /** Bytes that installing would download. */
    public static long downloadSize() {
        Build b = build();
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
        Build b = build();
        return b == null ? null : install(b, installedPath(), progress);
    }

    /** Downloads, extracts and tries {@code b}; {@code target} only stays if it runs. */
    static Path install(Build b, Path target, DoubleConsumer progress) throws IOException, InterruptedException {
        Path zip = target.resolveSibling("ffmpeg-download.zip");
        try {
            Downloads.fetch(b.url(), b.size(), b.sha256(), zip, "el conversor de video", progress);
            extract(zip, b.entry(), target);
        } finally {
            Files.deleteIfExists(zip);
        }
        target.toFile().setExecutable(true);
        if (!Processes.quiet(List.of(target.toString(), "-version"), null, 15).ok()) {
            Files.deleteIfExists(target);
            throw new IOException("El conversor de video descargado no funciona en este computador");
        }
        return target;
    }

    /** Only that one entry, always to {@code target}: names inside a zip never decide where a file lands. */
    private static void extract(Path zip, String entry, Path target) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory() || !(e.getName().equals(entry) || e.getName().endsWith(entry))) continue;
                copy(in, target);
                return;
            }
        }
        throw new IOException("La descarga del conversor de video no trae el programa");
    }

    private static void copy(InputStream in, Path target) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Build build() {
        String arch = System.getProperty("os.arch", "");
        if (Os.mac() && (arch.equals("aarch64") || arch.equals("arm64"))) return MAC_ARM64;
        if (Os.windows() && (arch.equals("amd64") || arch.equals("x86_64"))) return WINDOWS_AMD64;
        return null;
    }

    private static Path installedPath() {
        return Settings.get().dir().resolve("tools").resolve("ffmpeg").resolve(EXE);
    }
}

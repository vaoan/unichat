package app.unichat.cloak;

import app.unichat.settings.Settings;
import app.unichat.support.Archives;
import app.unichat.support.Downloads;
import app.unichat.support.Folders;
import app.unichat.support.I18n;
import app.unichat.support.Os;
import app.unichat.support.Processes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * What protecting a picture needs, installed on first use into {@code <data>/tools/cloak/}: uv (pinned), which brings
 * Python and the packages in {@code requirements.txt} (pinned with their checksums, wheels only: nothing is compiled),
 * Stable Diffusion 1.4's image encoder weights (Hugging Face, pinned) and Mist's target pattern (GitHub, pinned). The
 * script, {@code cloak.py}, is Unichat's own and is written there again before each run.
 */
public final class CloakTool {
    /** Bumped when anything below changes: an older installation is then done again. */
    static final String VERSION = "1";
    static final String PYTHON = "3.12";

    record Pinned(String url, long size, String sha256) {}

    private static final Pinned UV_MAC_ARM64 = new Pinned(
            "https://github.com/astral-sh/uv/releases/download/0.12.21/uv-aarch64-apple-darwin.tar.gz",
            17_001_427L, "b88bda573e566ef9bced66b155fe0408626fbbc053aee1c30ba686f0728c9447");
    private static final Pinned UV_WINDOWS_AMD64 = new Pinned(
            "https://github.com/astral-sh/uv/releases/download/0.12.21/uv-x86_64-pc-windows-msvc.zip",
            17_992_232L, "5d223efa0bf00208c3853246af09420419dfbd352536aa6bb8163d6170e23890");
    static final Pinned WEIGHTS = new Pinned("https://huggingface.co/CompVis/stable-diffusion-v1-4/resolve/"
            + "133a221b8aa7292a167afc5127cb63fb5005638b/vae/diffusion_pytorch_model.fp16.safetensors",
            167_335_342L, "4fbcf0ebe55a0984f5a5e00d8c4521d52359af7229bb4d81890039d2aa16dd7c");
    static final Pinned TARGET = new Pinned("https://raw.githubusercontent.com/psyker-team/mist/"
            + "99f5f3c455022bef77ef25ba440a4289ea472d25/MIST.png",
            160_260L, "d9111f2dce73e73f2e932e5ada87f17a288f53240bdf5978b9aaeb2a3093f361");
    /** What the whole installation downloads, roughly (PyTorch's Windows build is three times the Mac's). */
    private static final long DOWNLOAD_MAC_MB = 330;
    private static final long DOWNLOAD_WINDOWS_MB = 500;
    private static final long UV_SECONDS = 15 * 60;
    private static final long PACKAGES_SECONDS = 45 * 60;

    /** Where installing is at: a text key ({@code cloak.install.*}) and 0..1, or -1 when it can't tell. */
    public interface Progress {
        void at(String stepKey, double fraction);
    }

    private final Path dir;

    public CloakTool(Path dir) {
        this.dir = dir;
    }

    /** The installation in Unichat's data folder. */
    public static CloakTool standard() {
        return new CloakTool(Settings.get().dir().resolve("tools").resolve("cloak"));
    }

    /** Whether there's a build of everything for this computer: Macs with Apple chips and 64-bit Windows. */
    public static boolean canInstall() { return uvBuild() != null; }

    /** Megabytes a first use downloads. */
    public static long downloadMb() { return Os.windows() ? DOWNLOAD_WINDOWS_MB : DOWNLOAD_MAC_MB; }

    public boolean isInstalled() {
        try {
            return Files.isExecutable(python()) && Files.isRegularFile(dir.resolve("vae.safetensors"))
                    && Files.isRegularFile(dir.resolve("target.png"))
                    && VERSION.equals(Files.readString(dir.resolve("installed")).strip());
        } catch (IOException e) {
            return false;
        }
    }

    /** Installs what's missing. Blocking; one at a time. Interrupting the thread stops it. */
    public synchronized void install(Progress progress) throws IOException, InterruptedException {
        if (isInstalled()) return;
        Pinned uvBuild = uvBuild();
        if (uvBuild == null) throw new IOException(I18n.t("cloak.unsupported"));
        Files.deleteIfExists(dir.resolve("installed"));
        Path uv = uv();
        Path archive = dir.resolve(uvBuild.url().substring(uvBuild.url().lastIndexOf('/') + 1));
        try {
            Downloads.fetch(uvBuild.url(), uvBuild.size(), uvBuild.sha256(), archive, I18n.t("download.what.cloak"),
                    f -> progress.at("cloak.install.tools", f));
            if (!Archives.extractOne(archive, uv.getFileName().toString(), uv)) throw new IOException(I18n.t("cloak.error.install"));
        } finally {
            Files.deleteIfExists(archive);
        }
        uv.toFile().setExecutable(true);
        progress.at("cloak.install.python", -1);
        Folders.deleteQuietly(dir.resolve("env"));
        run(venvCommand(), UV_SECONDS);
        progress.at("cloak.install.packages", -1);
        copyResource("requirements.txt");
        run(packagesCommand(), PACKAGES_SECONDS);
        Folders.deleteQuietly(dir.resolve("cache"));
        Downloads.fetch(WEIGHTS.url(), WEIGHTS.size(), WEIGHTS.sha256(), dir.resolve("vae.safetensors"),
                I18n.t("download.what.cloak"), f -> progress.at("cloak.install.model", f));
        Downloads.fetch(TARGET.url(), TARGET.size(), TARGET.sha256(), dir.resolve("target.png"),
                I18n.t("download.what.cloak"), f -> { });
        Files.writeString(dir.resolve("installed"), VERSION);
    }

    /** The command that protects {@code in} into {@code out} (a PNG), with the script written out first. */
    public List<String> protectCommand(Path in, Path out, CloakLevel level) throws IOException {
        Path script = copyResource("cloak.py");
        return List.of(python().toString(), "-I", script.toString(), "--weights", dir.resolve("vae.safetensors").toString(),
                "--target", dir.resolve("target.png").toString(), "--input", in.toString(), "--output", out.toString(),
                "--steps", String.valueOf(level.steps()));
    }

    List<String> venvCommand() {
        return List.of(uv().toString(), "venv", "--no-config", "--quiet", "--python", PYTHON,
                "--python-preference", "only-managed", dir.resolve("env").toString());
    }

    /** Only the pinned versions with their checksums, and only ready-made wheels. */
    List<String> packagesCommand() {
        return List.of(uv().toString(), "pip", "install", "--no-config", "--quiet", "--python", python().toString(),
                "--require-hashes", "--no-build", "-r", dir.resolve("requirements.txt").toString());
    }

    Path python() {
        return Os.windows() ? dir.resolve("env").resolve("Scripts").resolve("python.exe")
                : dir.resolve("env").resolve("bin").resolve("python3");
    }

    private Path uv() {
        return dir.resolve("uv").resolve(Os.windows() ? "uv.exe" : "uv");
    }

    /** uv with its downloads, cache and settings inside this folder, never the person's own. */
    private void run(List<String> command, long seconds) throws IOException, InterruptedException {
        ProcessBuilder b = new ProcessBuilder(command).redirectErrorStream(true);
        b.environment().put("UV_PYTHON_INSTALL_DIR", dir.resolve("python").toString());
        b.environment().put("UV_CACHE_DIR", dir.resolve("cache").toString());
        b.environment().put("UV_NO_PROGRESS", "1");
        b.environment().remove("VIRTUAL_ENV");
        Processes.Result r = Processes.capture(b, seconds);
        if (!r.ok()) {
            System.err.println("Image protection: installing failed (" + command.get(1) + ", exit " + r.exit() + "): "
                    + lastLines(r.out()));
            throw new IOException(I18n.t("cloak.error.install"));
        }
    }

    private Path copyResource(String name) throws IOException {
        Path target = dir.resolve(name);
        Files.createDirectories(dir);
        try (InputStream in = CloakTool.class.getResourceAsStream("/app/unichat/cloak/" + name)) {
            if (in == null) throw new IOException("missing resource " + name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    static String lastLines(String out) {
        List<String> lines = new ArrayList<>(out.lines().filter(l -> !l.isBlank()).toList());
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
    }

    private static Pinned uvBuild() {
        String arch = System.getProperty("os.arch", "");
        if (Os.mac() && (arch.equals("aarch64") || arch.equals("arm64"))) return UV_MAC_ARM64;
        if (Os.windows() && (arch.equals("amd64") || arch.equals("x86_64"))) return UV_WINDOWS_AMD64;
        return null;
    }
}

package app.unichat.server;

import app.unichat.support.Os;

import java.nio.file.Path;

/** Where the native server stack runs: release asset naming and the per-user data folder. */
public enum Platform {
    MAC_ARM64("darwin", "arm64", "", true),
    WINDOWS_AMD64("windows", "amd64", ".exe", false);

    private final String os;
    private final String arch;
    private final String exeSuffix;
    private final boolean officialBuilds;

    Platform(String os, String arch, String exeSuffix, boolean officialBuilds) {
        this.os = os;
        this.arch = arch;
        this.exeSuffix = exeSuffix;
        this.officialBuilds = officialBuilds;
    }

    public String os() { return os; }
    public String arch() { return arch; }
    public String exeSuffix() { return exeSuffix; }
    /** Whether the bridges publish binaries for it (mautrix only does for Linux and macOS). */
    public boolean officialBuilds() { return officialBuilds; }

    /** The machine this runs on, or null when the native stack isn't supported (Intel Mac, Linux, 32-bit). */
    public static Platform current() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (Os.mac() && (arch.equals("aarch64") || arch.equals("arm64"))) return MAC_ARM64;
        if (Os.windows() && (arch.equals("amd64") || arch.equals("x86_64"))) return WINDOWS_AMD64;
        return null;
    }

    /** ~/Library/Application Support/Unichat/server or %LOCALAPPDATA%\Unichat\server; -Dunichat.serverDir wins. */
    public Path dataDir() {
        String override = System.getProperty("unichat.serverDir");
        if (override != null) return Path.of(override);
        String home = System.getProperty("user.home");
        if (this == MAC_ARM64) return Path.of(home, "Library", "Application Support", "Unichat", "server");
        String local = System.getenv("LOCALAPPDATA");
        return (local != null ? Path.of(local) : Path.of(home, "AppData", "Local")).resolve("Unichat").resolve("server");
    }
}

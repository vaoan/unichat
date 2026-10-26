package app.unichat.server;

import java.nio.file.Path;

/** Where the native server stack runs: release asset naming and the per-user data folder. */
public enum Platform {
    MAC_ARM64("darwin", "arm64", ""),
    WINDOWS_AMD64("windows", "amd64", ".exe");

    private final String os;
    private final String arch;
    private final String exeSuffix;

    Platform(String os, String arch, String exeSuffix) {
        this.os = os;
        this.arch = arch;
        this.exeSuffix = exeSuffix;
    }

    public String os() { return os; }
    public String arch() { return arch; }
    public String exeSuffix() { return exeSuffix; }

    /** The machine this runs on, or null when the native stack isn't supported (Intel Mac, Linux, 32-bit). */
    public static Platform current() {
        String name = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (name.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"))) return MAC_ARM64;
        if (name.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"))) return WINDOWS_AMD64;
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

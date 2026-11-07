package app.unichat.support;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Command-line tools shipped inside the app's {@code app/server/} folder (gif2webp, whisper-cli). */
public final class BundledTools {
    private BundledTools() {}

    /**
     * {@code name} (".exe" added on Windows): {@code -D<property>} (development), the copy inside the app at
     * {@code app/server/<subdir>}, or one on the PATH; null when there's none.
     */
    public static Path find(String property, String subdir, String name) {
        String exe = Os.windows() ? name + ".exe" : name;
        String o = System.getProperty(property);
        if (o != null) return Files.isExecutable(Path.of(o)) ? Path.of(o) : null;
        Path f = appFile(subdir == null ? exe : subdir + "/" + exe);
        return f != null && Files.isExecutable(f) ? f : onPath(exe);
    }

    /**
     * Where a file shipped under {@code app/server/} is (Unichat.app/Contents/app/server on macOS, Unichat\\app\\server
     * on Windows), whether or not it's there; null when this isn't a packaged app.
     */
    public static Path appFile(String relative) {
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null) return null;
        Path l = Path.of(launcher);
        Path app = Os.windows() ? l.getParent().resolve("app") : l.getParent().resolveSibling("app");
        return app.resolve("server").resolve(relative);
    }

    /** {@code exe} (exact file name) in a PATH folder, or null. */
    public static Path onPath(String exe) {
        for (String d : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (d.isBlank()) continue;
            Path f = Path.of(d, exe);
            if (Files.isExecutable(f)) return f;
        }
        return null;
    }
}

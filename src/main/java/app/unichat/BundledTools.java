package app.unichat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Command-line tools shipped inside the app's {@code app/server/} folder (gif2webp, whisper-cli). */
public final class BundledTools {
    private BundledTools() {}

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * {@code name} (".exe" added on Windows): {@code -D<property>} (development), the copy inside the app at
     * {@code app/server/<subdir>}, or one on the PATH; null when there's none.
     */
    public static Path find(String property, String subdir, String name) {
        String exe = windows() ? name + ".exe" : name;
        String o = System.getProperty(property);
        if (o != null) return Files.isExecutable(Path.of(o)) ? Path.of(o) : null;
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null) {
            Path l = Path.of(launcher);
            Path app = windows() ? l.getParent().resolve("app") : l.getParent().resolveSibling("app");
            Path dir = app.resolve("server");
            Path f = (subdir == null ? dir : dir.resolve(subdir)).resolve(exe);
            if (Files.isExecutable(f)) return f;
        }
        return onPath(exe);
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

package app.unichat.video;

import app.unichat.support.Os;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * gallery-dl, for the sites yt-dlp doesn't download (Pixiv, FANBOX, Misskey…), when the person has it: Unichat doesn't
 * install it (it's Python). Homebrew's, the PATH's, or the one InstaTray installs on Windows. Null when there's none:
 * those links then say gallery-dl is missing.
 */
public final class GalleryDl {
    private static final String EXE = Os.windows() ? "gallery-dl.exe" : "gallery-dl";

    private GalleryDl() {}

    /** {@code -Dunichat.galleryDl} is the only one considered when set. */
    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "where Homebrew and the usual installers put gallery-dl, looked in when it isn't on the PATH")
    public static @Nullable Path find() {
        String forced = System.getProperty("unichat.galleryDl");
        if (forced != null) return Files.isExecutable(Path.of(forced)) ? Path.of(forced) : null;
        List<Path> places = new ArrayList<>(List.of(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin")));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) places.add(Path.of(local, "InstaTray", "tools", "Scripts")); // InstaTray's own on Windows
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(java.io.File.pathSeparator)) if (!d.isBlank()) places.add(Path.of(d));
        for (Path d : places) {
            Path f = d.resolve(EXE);
            if (Files.isExecutable(f)) return f;
        }
        return null;
    }
}

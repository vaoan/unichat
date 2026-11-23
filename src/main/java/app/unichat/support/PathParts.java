package app.unichat.support;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A path's name and folder, for paths that have them: every file the app handles is absolute and inside a folder, so
 * {@link Path#getFileName()} and {@link Path#getParent()} (null only for a root or a bare name) are never null here. A
 * path without one fails at once, saying which.
 */
public final class PathParts {
    private PathParts() {}

    /** The last part of {@code p}, as text: {@code a.jpg} of {@code /x/a.jpg}. */
    public static String name(Path p) {
        return Objects.requireNonNull(p.getFileName(), () -> p + " has no name").toString();
    }

    /** The folder {@code p} is in. */
    public static Path parent(Path p) {
        return Objects.requireNonNull(p.getParent(), () -> p + " is in no folder");
    }
}

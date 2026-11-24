package app.unichat;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A named pipe for a test (not on Windows): each line is read the moment it's written, and its end says there's no more. */
public final class Fifos {
    private Fifos() {}

    /** Makes the pipe at {@code path} and gives it back. */
    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "mkfifo with a path the test made; nothing from outside")
    public static Path at(Path path) throws IOException, InterruptedException {
        Process mkfifo = new ProcessBuilder("mkfifo", path.toString()).inheritIO().start(); // lgtm[java/relative-path-command] the system's mkfifo, found as a shell finds it; a test only
        assertEquals(0, mkfifo.waitFor(), "mkfifo " + path);
        return path;
    }
}

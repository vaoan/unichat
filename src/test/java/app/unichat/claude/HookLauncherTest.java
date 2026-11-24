package app.unichat.claude;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hooks run these programs as {@code .claude/hooks/java.sh} does: Java's source launcher, with no classpath. A class
 * here that needs a library (JSpecify's {@code @Nullable}, once) fails every hook run, the edit hook and the stop check
 * among them, with nothing in the build to say so: this runs one the hooks' way.
 */
class HookLauncherTest {
    @TempDir
    Path dir;

    @Test
    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "the repo's own hook script with a file this test wrote")
    void theLessonsCheckStartsAsTheHooksRunIt() throws IOException, InterruptedException {
        Path clean = Files.writeString(dir.resolve("Clean.java"), "final class Clean {\n}\n");
        Process p = new ProcessBuilder("bash", ".claude/hooks/java.sh", "Lessons", clean.toString())
                .redirectErrorStream(true).start();
        String said = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "java.sh Lessons on a clean file: " + said);
        assertEquals("", said, "nothing to say about a clean file");
    }
}

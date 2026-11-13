package app.unichat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests left their temp files in the system's temp folder for good (hundreds of folders after a few runs): anything
 * a test makes there, with or without a @TempDir, goes under target/test-tmp, which is emptied when the run ends.
 */
class TestTempTest {
    @Test
    void aTestsTempFilesGoUnderTarget() throws Exception {
        Path made = Files.createTempDirectory("unichat-library");
        try {
            assertTrue(made.toAbsolutePath().startsWith(TestTemp.folder()), made.toString());
            assertTrue(TestTemp.folder().endsWith(Path.of("target", "test-tmp")), TestTemp.folder().toString());
        } finally {
            Files.deleteIfExists(made);
        }
    }

    @Test
    void emptyingTakesEverythingInsideAndKeepsTheFolder(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("a/b"));
        Files.writeString(dir.resolve("a/b/f.txt"), "x");
        Files.writeString(dir.resolve("g.txt"), "y");

        TestTemp.empty(dir);

        assertTrue(Files.isDirectory(dir));
        try (var s = Files.list(dir)) {
            assertEquals(0, s.count());
        }
    }
}

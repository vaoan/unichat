package app.unichat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppLogTest {
    @Test
    void aLineFromAnInterruptedThreadLeavesTheLogOpen(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("unichat.log");
        try (OutputStream out = AppLog.open(log)) {
            Thread.currentThread().interrupt();
            try {
                out.write("interrupted\n".getBytes(StandardCharsets.UTF_8));
            } finally {
                Thread.interrupted(); // the test's thread is given back as it was
            }
            out.write("next\n".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("interrupted\nnext\n", Files.readString(log));
    }

    @Test
    void itAppendsToTheLogThere(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("unichat.log");
        Files.writeString(log, "before\n");
        try (OutputStream out = AppLog.open(log)) {
            out.write("after\n".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("before\nafter\n", Files.readString(log));
    }
}

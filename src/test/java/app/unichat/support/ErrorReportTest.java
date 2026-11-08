package app.unichat.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorReportTest {
    @Test
    void startsWithWhatRunsHere() {
        String r = ErrorReport.text("1.2.0", "Windows 11 10.0 (amd64)", "21.0.5 Eclipse Adoptium", List.of("a"));
        assertTrue(r.startsWith("Unichat 1.2.0\nOS: Windows 11 10.0 (amd64)\nJava: 21.0.5 Eclipse Adoptium\n"), r);
        assertTrue(r.endsWith("a\n"), r);
    }

    @Test
    void keepsOnlyTheLastLines() {
        List<String> log = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) log.add("line " + i);
        log.add("");
        log.add("");
        String r = ErrorReport.text("1", "os", "java", log);
        assertTrue(r.contains("(last 300 lines)"), r);
        assertTrue(r.contains("line 701\n"));
        assertFalse(r.contains("line 700\n"));
        assertTrue(r.endsWith("line 1000\n"), "blank lines at the end don't count");
    }

    @Test
    void redactsAgainWhatAnOlderVersionWrote() {
        String r = ErrorReport.text("1", "os", "java", List.of("sent to @juan:example.org at juan@example.org, +57 300 111 2233"));
        assertFalse(r.contains("juan"), r);
        assertFalse(r.contains("300 111"), r);
    }

    @Test
    void readsTheLogFileOrSaysNothingWithout(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("unichat.log");
        assertTrue(ErrorReport.of(log).contains("(last 0 lines)"));
        Files.writeString(log, "=== Unichat ===\r\nUpdate check failed: HTTP 404\r\n");
        String r = ErrorReport.of(log);
        assertTrue(r.endsWith("=== Unichat ===\nUpdate check failed: HTTP 404\n"), r);
        assertEquals(AppVersion.current(), r.lines().findFirst().orElseThrow().substring("Unichat ".length()));
    }
}

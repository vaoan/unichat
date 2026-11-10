package app.unichat.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The stream's name and what goes in it. Writing it is NTFS only (Windows): a Mac or Linux has no alternate data
 * streams, so the write itself can't be checked here; only that nothing is written outside Windows.
 */
class MarkOfTheWebTest {
    @Test
    void theZoneSaysInternet() {
        assertEquals("[ZoneTransfer]\r\nZoneId=3\r\n", MarkOfTheWeb.ZONE);
    }

    @Test
    void itIsTheFilesZoneIdentifierStream() {
        Path file = Path.of("folder", "invoice.exe");
        assertEquals(file + ":Zone.Identifier", MarkOfTheWeb.stream(file));
    }

    @Test
    void outsideWindowsNothingIsWritten(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(Os.windows());
        Path file = Files.writeString(dir.resolve("a.exe"), "x");
        MarkOfTheWeb.mark(file);
        try (var in = Files.list(dir)) {
            assertEquals(List.of(file), in.toList(), "a colon is a plain name here: it would be a stray file");
        }
    }
}

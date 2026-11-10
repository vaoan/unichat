package app.unichat.support;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Marks a file as come from the Internet on Windows (its {@code Zone.Identifier} stream), as a browser does: without
 * it, a received ".exe" ran with no SmartScreen warning. Nothing elsewhere.
 */
public final class MarkOfTheWeb {
    /** Zone 3: the Internet. */
    static final String ZONE = "[ZoneTransfer]\r\nZoneId=3\r\n";

    private MarkOfTheWeb() {}

    /**
     * Marks {@code file}, on Windows only. Failing is ignored: FAT and exFAT drives have no streams. java.io, not
     * {@code Path.of}: NIO refuses the colon in a Windows path.
     */
    public static void mark(Path file) {
        if (!Os.windows()) return;
        try (OutputStream out = new FileOutputStream(stream(file))) {
            out.write(ZONE.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException e) {
            // no streams on this drive: the file stays, unmarked
        }
    }

    /** The stream's name: "file.exe:Zone.Identifier". */
    static String stream(Path file) {
        return file + ":Zone.Identifier";
    }
}

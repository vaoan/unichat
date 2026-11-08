package app.unichat.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * What "Copiar informe de errores" puts on the clipboard: Unichat's version, the system, Java and the end of
 * {@code unichat.log}. The log is already redacted as it is written; each line passes through {@link Redactor} again,
 * since an older version may have written it.
 */
public final class ErrorReport {
    /** Enough for the last start and its errors; little enough to paste in an issue. */
    public static final int LINES = 300;

    private ErrorReport() {}

    /** The report for this computer, with the last {@link #LINES} lines of {@code log} (none when it can't be read). */
    public static String of(Path log) {
        List<String> lines;
        try {
            lines = Files.exists(log) ? lines(new String(Files.readAllBytes(log), StandardCharsets.UTF_8)) : List.of();
        } catch (IOException e) {
            lines = List.of("(" + e.getClass().getSimpleName() + ")");
        }
        return text(AppVersion.current(), System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")", System.getProperty("java.version")
                + " " + System.getProperty("java.vendor", ""), lines);
    }

    static List<String> lines(String log) {
        return Arrays.asList(log.split("\r?\n", -1));
    }

    /** Pure: the header, then the last {@link #LINES} non-trailing lines of {@code log}, each redacted. */
    static String text(String version, String os, String java, List<String> log) {
        int end = log.size();
        while (end > 0 && log.get(end - 1).isBlank()) end--;
        StringBuilder sb = new StringBuilder()
                .append("Unichat ").append(version).append('\n')
                .append("OS: ").append(os).append('\n')
                .append("Java: ").append(java.strip()).append('\n')
                .append("--- unichat.log (last ").append(Math.min(LINES, end)).append(" lines) ---\n");
        for (String line : log.subList(Math.max(0, end - LINES), end)) sb.append(Redactor.redact(line)).append('\n');
        return sb.toString();
    }
}

package app.unichat.ui;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads text aloud with the OS speech engine: SAPI via PowerShell on Windows, {@code say} on macOS,
 * {@code spd-say}/{@code espeak} on Linux. Text always goes through stdin, never the command line,
 * so message content cannot inject arguments. Utterances are queued, one at a time.
 */
public final class Speaker {
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "speaker");
        t.setDaemon(true);
        return t;
    });

    private Speaker() {}

    public static void say(String text) {
        if (text == null || text.isBlank()) return;
        String clean = text.length() > 400 ? text.substring(0, 400) : text;
        QUEUE.submit(() -> speakNow(clean));
    }

    static List<String> command() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::InputEncoding = [Text.Encoding]::UTF8; "
                            + "Add-Type -AssemblyName System.Speech; "
                            + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                            + "$s.Speak([Console]::In.ReadToEnd())");
        }
        if (os.contains("mac")) return List.of("say", "-f", "-");
        return List.of("sh", "-c", "if command -v spd-say >/dev/null; then spd-say -e; else espeak --stdin; fi");
    }

    private static void speakNow(String text) {
        try {
            Process p = new ProcessBuilder(command()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream in = p.getOutputStream()) {
                in.write(text.getBytes(StandardCharsets.UTF_8));
            }
            p.waitFor();
        } catch (Exception e) {
            System.err.println("Speech not available: " + e);
        }
    }
}

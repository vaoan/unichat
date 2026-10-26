package app.unichat;

import app.unichat.settings.Settings;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;

/**
 * Copies stdout/stderr to {@code <data dir>/logs/unichat.log}: a packaged app opened from Finder or the Start menu
 * has no console, so this is where its errors end up. The previous log (over 5 MB) is kept as unichat.log.1.
 */
final class AppLog {
    private static final long MAX = 5L * 1024 * 1024;

    private AppLog() {}

    static void install() {
        try {
            Path dir = Settings.dataDir().resolve("logs");
            Files.createDirectories(dir);
            Path log = dir.resolve("unichat.log");
            if (Files.exists(log) && Files.size(log) > MAX) {
                Files.move(log, dir.resolve("unichat.log.1"), StandardCopyOption.REPLACE_EXISTING);
            }
            OutputStream file = new FileOutputStream(log.toFile(), true);
            String header = "\n=== Unichat " + LocalDateTime.now() + " ===\n";
            file.write(header.getBytes(StandardCharsets.UTF_8));
            System.setOut(new PrintStream(new Tee(System.out, file), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new Tee(System.err, file), true, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("No log file: " + e);
        }
    }

    private static final class Tee extends OutputStream {
        private final OutputStream a, b;

        Tee(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public synchronized void write(int c) throws IOException {
            a.write(c);
            b.write(c);
        }

        @Override
        public synchronized void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }

        @Override
        public synchronized void flush() throws IOException {
            a.flush();
            b.flush();
        }
    }
}

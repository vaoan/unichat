package app.unichat.server;

import app.unichat.support.Redactor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * Copies a process's output to its log with personal data removed ({@link Redactor}); past {@link #MAX_LOG} the log
 * is moved to {@code <log>.1} (one copy kept).
 * <p>
 * Cheap per line: no size check (the byte count starts from the file's size when it is opened and is kept in
 * memory), and a flush only when it matters. A line whose JSON {@code "level"} is warn, error or fatal, or that
 * isn't JSON, is flushed at once; the others when a later line arrives at least {@link #FLUSH_EVERY_MS} after the
 * last flush, or when the stream ends or the log rotates. No timer thread.
 * <p>
 * Whatever fails here (log not writable, disk full, out of memory), the output keeps being read: with nobody
 * reading, the pipe fills and the process blocks on its next log line, alive but deaf (the Discord bridge sat
 * a day in write() after an OutOfMemoryError killed this thread). A line that can't be written is lost and the
 * next one tries again.
 */
final class LogPump {
    static final long MAX_LOG = 5L * 1024 * 1024;
    static final long FLUSH_EVERY_MS = 1000;
    private static final String LEVEL_KEY = "\"level\":\"";
    private static final String[] URGENT = {"warn", "error", "fatal", "panic"};

    private final Path log;
    private final LongSupplier clockMs;
    private final ToLongFunction<Path> sizeOf;
    private OutputStream out;
    private long bytes;
    private long lastFlush;

    private LogPump(Path log, LongSupplier clockMs, ToLongFunction<Path> sizeOf) {
        this.log = log;
        this.clockMs = clockMs;
        this.sizeOf = sizeOf;
    }

    static void run(Process p, Path log, Consumer<String> read, Runnable afterLine) {
        run(p, log, read, afterLine, System::currentTimeMillis, LogPump::fileSizeOrZero);
    }

    /** {@link #run(Process, Path, Consumer, Runnable)} with the clock and the file size given (tests). */
    static void run(Process p, Path log, Consumer<String> read, Runnable afterLine, LongSupplier clockMs,
                    ToLongFunction<Path> sizeOf) {
        new LogPump(log, clockMs, sizeOf).copy(p, read, afterLine);
    }

    static long fileSizeOrZero(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return 0;
        }
    }

    private void copy(Process p, Consumer<String> read, Runnable afterLine) {
        InputStream raw = p.getInputStream(); // NOPMD: CloseResource, the process's own; the JDK closes it when it exits
        BufferedReader in = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8)); // NOPMD: CloseResource, as raw
        boolean reported = false;
        try { // NOPMD: UseTryWithResources, the process's stream; the JDK closes it when it exits
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    read.accept(line);
                    write(Redactor.redact(line) + "\n", urgent(line));
                    reported = false;
                    afterLine.run();
                } catch (Throwable t) {
                    out = closeQuietly(out);
                    if (!reported) System.err.println("Log copy failing (" + log.getFileName() + "): " + t);
                    reported = true;
                }
            }
        } catch (IOException e) {
            // Output closed: the process is gone.
        } catch (Throwable t) {
            // Reading itself failed (out of memory on a huge line): from here on only drain, into a fixed buffer.
            System.err.println("Log copy stopped (" + log.getFileName() + "): " + t);
            byte[] buf = new byte[8192];
            while (true) {
                try {
                    if (raw.read(buf) < 0) break;
                } catch (IOException e) {
                    break;
                } catch (Throwable again) {
                    // keep draining
                }
            }
        } finally {
            closeQuietly(out);
        }
    }

    private void write(String text, boolean flushNow) throws IOException {
        if (out == null) {
            bytes = sizeOf.applyAsLong(log);
            // Buffered: the file stream writes at once, so "flush" would save nothing without it.
            out = new java.io.BufferedOutputStream(Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            lastFlush = clockMs.getAsLong();
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        out.write(data);
        bytes += data.length;
        if (bytes > MAX_LOG) {
            out.close();
            out = null;
            Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        long now = clockMs.getAsLong();
        if (flushNow || now - lastFlush >= FLUSH_EVERY_MS) {
            out.flush();
            lastFlush = now;
        }
    }

    /** A warn/error/fatal line, or one that isn't JSON: read with a substring check, not a parse. */
    static boolean urgent(String line) {
        int at = line.indexOf(LEVEL_KEY);
        if (at < 0) return true;
        int v = at + LEVEL_KEY.length();
        for (String level : URGENT) {
            if (line.regionMatches(true, v, level, 0, level.length())) return true;
        }
        return false;
    }

    private static OutputStream closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (Throwable ignored) {
                // nothing to save
            }
        }
        return null;
    }
}

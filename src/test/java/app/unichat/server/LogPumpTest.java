package app.unichat.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogPumpTest {
    private static final String WARN = "{\"level\":\"warn\",\"message\":\"a\"}";
    private static final String INFO = "{\"level\":\"info\",\"message\":\"b\"}";

    private static Process processReading(InputStream in) {
        return new Process() {
            @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            @Override public InputStream getInputStream() { return in; }
            @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
        };
    }

    /** A pump running on its own thread over a pipe: lines go in one at a time, each waited on by afterLine. */
    private static final class Live implements AutoCloseable {
        final PipedOutputStream feed = new PipedOutputStream();
        final BlockingQueue<Boolean> done = new ArrayBlockingQueue<>(100);
        final Thread thread;

        Live(Path log, AtomicLong clock, java.util.function.ToLongFunction<Path> size) throws IOException {
            PipedInputStream in = new PipedInputStream(feed, 1 << 16);
            thread = new Thread(() -> LogPump.run(processReading(in), log, l -> { }, () -> done.add(true), clock::get, size));
            thread.setDaemon(true);
            thread.start();
        }

        void send(String line) throws Exception {
            feed.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            feed.flush();
            done.take(); // the pump handled it; JUnit's global timeout is the only limit
        }

        @Override public void close() throws Exception {
            feed.close();
            thread.join(); // lesson-ok: test-future-join (a Thread's join, interruptible; the pump ends with its input)
        }
    }

    @Test
    void aWarningIsOnDiskAtOnce(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("b.log");
        try (Live live = new Live(log, new AtomicLong(0), LogPump::fileSizeOrZero)) {
            live.send(WARN);
            assertTrue(Files.readString(log).contains(WARN));
        }
    }

    @Test
    void aLineThatIsNotJsonIsOnDiskAtOnce(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("b.log");
        try (Live live = new Live(log, new AtomicLong(0), LogPump::fileSizeOrZero)) {
            live.send("panic: something plain");
            assertTrue(Files.readString(log).contains("panic: something plain"));
        }
    }

    @Test
    void anUppercaseErrorLevelIsOnDiskAtOnce(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("b.log");
        String line = "{\"level\":\"ERROR\",\"message\":\"x\"}";
        try (Live live = new Live(log, new AtomicLong(0), LogPump::fileSizeOrZero)) {
            live.send(line);
            assertTrue(Files.readString(log).contains(line));
        }
    }

    @Test
    void infoLinesAreFlushedWithinTheNextFlush(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("b.log");
        AtomicLong clock = new AtomicLong(0);
        try (Live live = new Live(log, clock, LogPump::fileSizeOrZero)) {
            live.send(INFO);
            clock.set(500);
            live.send(INFO);
            assertEquals("", Files.readString(log), "info lines inside the second wait in memory");
            clock.set(1000);
            live.send(INFO);
            assertEquals(3, Files.readAllLines(log).size(), "the next line a second after the last flush flushes all");
        }
    }

    @Test
    void aWarningFlushesTheInfoLinesBeforeIt(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("b.log");
        try (Live live = new Live(log, new AtomicLong(0), LogPump::fileSizeOrZero)) {
            live.send(INFO);
            live.send(WARN);
            assertEquals(2, Files.readAllLines(log).size());
        }
    }

    @Test
    void whatIsWaitingIsOnDiskWhenTheStreamEnds(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("b.log");
        LogPump.run(processReading(new ByteArrayInputStream((INFO + "\n" + INFO + "\n").getBytes(StandardCharsets.UTF_8))),
                log, l -> { }, () -> { }, () -> 0, LogPump::fileSizeOrZero);
        assertEquals(2, Files.readAllLines(log).size());
    }

    @Test
    void rotationCountsTheFileAlreadyThere(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("b.log");
        Files.write(log, new byte[(int) LogPump.MAX_LOG - 10]);
        String line = "{\"level\":\"info\",\"m\":1}"; // 21 chars + newline: over by 12
        LogPump.run(processReading(new ByteArrayInputStream((line + "\n").getBytes(StandardCharsets.UTF_8))),
                log, l -> { }, () -> { }, () -> 0, LogPump::fileSizeOrZero);
        assertTrue(Files.exists(dir.resolve("b.log.1")), "the old file became .1");
        assertFalse(Files.exists(log), "and the next line starts a new file");
    }

    @Test
    void noSizeCheckPerLine(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("b.log");
        AtomicInteger sizeCalls = new AtomicInteger();
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 50; i++) many.append(INFO).append('\n');
        LogPump.run(processReading(new ByteArrayInputStream(many.toString().getBytes(StandardCharsets.UTF_8))),
                log, l -> { }, () -> { }, () -> 0, f -> {
                    sizeCalls.incrementAndGet();
                    return 0;
                });
        assertEquals(50, Files.readAllLines(log).size());
        assertEquals(1, sizeCalls.get());
    }

    @Test
    void readAndAfterLineStillRunForEveryLine(@TempDir Path dir) throws Exception {
        AtomicInteger read = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        LogPump.run(processReading(new ByteArrayInputStream((INFO + "\n" + WARN + "\nplain\n").getBytes(StandardCharsets.UTF_8))),
                dir.resolve("b.log"), l -> read.incrementAndGet(), after::incrementAndGet, () -> 0, LogPump::fileSizeOrZero);
        assertEquals(3, read.get());
        assertEquals(3, after.get());
    }
}

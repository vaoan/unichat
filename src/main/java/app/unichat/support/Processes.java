package app.unichat.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The one way Unichat runs a program: one that sticks can't hold Unichat past its time, a full output pipe can't stick
 * one, and one stopped (out of time, or its thread interrupted) is stopped with its children.
 */
public final class Processes {
    /** A child it leaves running can keep the pipe open forever: past this, what was read is all there is. */
    private static final long READER_GRACE_MS = 2_000;

    private Processes() {}

    /**
     * How it ended.
     * @param finished false: it ran out of time and was killed
     * @param exit its exit code; -1 when it didn't finish
     * @param out what it printed, if it was captured
     */
    public record Result(boolean finished, int exit, String out) {
        public boolean ok() { return finished && exit == 0; }
    }

    /**
     * How long a program may take: in all, and without printing anything (one quiet that long is stuck, when a long
     * job's whole time can't be told ahead). Seconds; {@link Long#MAX_VALUE}: no such limit.
     */
    public record Limit(long seconds, long silentSeconds) {
        public static Limit of(long seconds) { return new Limit(seconds, Long.MAX_VALUE); }

        public static Limit whileTalking(long silentSeconds) { return new Limit(Long.MAX_VALUE, silentSeconds); }
    }

    /** Runs {@code cmd} with its output discarded. @param dir where it runs; null: wherever Unichat does */
    public static Result quiet(List<String> cmd, Path dir, long seconds) throws IOException, InterruptedException {
        ProcessBuilder b = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        if (dir != null) b.directory(dir.toFile());
        return capture(b, seconds);
    }

    /** Runs {@code cmd} and keeps its standard output; errors are left out, so they can't pass for the answer. */
    public static Result capture(List<String> cmd, long seconds) throws IOException, InterruptedException {
        return capture(new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD), seconds);
    }

    /** The same for a command that needs its own folder or redirects. It always gets an empty standard input. */
    public static Result capture(ProcessBuilder command, long seconds) throws IOException, InterruptedException {
        return capture(command, seconds, null);
    }

    /** The same, handing {@code eachLine} every line of output as it comes (a download's progress); null: none. */
    public static Result capture(ProcessBuilder command, long seconds, Consumer<String> eachLine)
            throws IOException, InterruptedException {
        return capture(command, Limit.of(seconds), null, eachLine);
    }

    /**
     * What all the others come to: {@code input} (null: none) is written to it while it runs, its output is read as it
     * comes, and past {@code limit} it's stopped with its children. Interrupting the waiting thread stops it the same
     * way, and this throws {@link InterruptedException}.
     */
    public static Result capture(ProcessBuilder command, Limit limit, String input, Consumer<String> eachLine)
            throws IOException, InterruptedException {
        Process p = command.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicLong heard = new AtomicLong(System.nanoTime());
        Thread reader = Thread.ofPlatform().daemon().name("process-output").start(() -> read(p, out, eachLine, heard));
        try {
            if (input == null) closeInput(p);
            else Thread.ofPlatform().daemon().name("process-input").start(() -> feed(p, input));
            boolean finished = waitFor(p, limit, heard);
            if (!finished) stop(p);
            reader.join(READER_GRACE_MS);
            return new Result(finished, finished ? p.exitValue() : -1, out.toString(StandardCharsets.UTF_8));
        } finally {
            if (p.isAlive()) stop(p);
        }
    }

    /**
     * The last {@code n} lines that say something, joined by " | ": a program ends with its reason. Empty when there's
     * none.
     */
    public static String lastLines(String output, int n) {
        List<String> lines = output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }

    /** Whether it ended within {@code limit}. */
    private static boolean waitFor(Process p, Limit limit, AtomicLong heard) throws InterruptedException {
        long start = System.nanoTime();
        long all = TimeUnit.SECONDS.toNanos(limit.seconds()), silence = TimeUnit.SECONDS.toNanos(limit.silentSeconds());
        while (true) {
            long now = System.nanoTime();
            long left = Math.min(all - (now - start), silence - (now - heard.get()));
            if (p.waitFor(Math.max(0, left), TimeUnit.NANOSECONDS)) return true;
            if (left <= 0) return false;
        }
    }

    /** Reads to the end whatever happens to the rest: a process nobody reads from stops in write(). */
    private static void read(Process p, ByteArrayOutputStream out, Consumer<String> eachLine, AtomicLong heard) {
        try (InputStream in = p.getInputStream()) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) >= 0; ) {
                heard.set(System.nanoTime());
                out.write(buf, 0, n);
                if (eachLine != null) split(buf, n, line, eachLine);
            }
            if (eachLine != null && line.size() > 0) eachLine.accept(line.toString(StandardCharsets.UTF_8));
        } catch (IOException ended) {
            // Killed, or its pipe closed.
        }
    }

    /** A carriage return ends a line too (progress bars). */
    private static void split(byte[] buf, int n, ByteArrayOutputStream line, Consumer<String> eachLine) {
        for (int i = 0; i < n; i++) {
            byte b = buf[i];
            if (b == '\n' || b == '\r') {
                if (line.size() > 0) eachLine.accept(line.toString(StandardCharsets.UTF_8));
                line.reset();
            } else {
                line.write(b);
            }
        }
    }

    private static void feed(Process p, String input) {
        try (OutputStream in = p.getOutputStream()) {
            in.write(input.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // It exited without reading it all: its exit code says why.
        }
    }

    private static void closeInput(Process p) {
        try {
            p.getOutputStream().close();
        } catch (IOException gone) {
            // It already exited.
        }
    }

    /** It and its children (a shell's, a node script's): one left behind keeps running and holding its files. */
    private static void stop(Process p) throws InterruptedException {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        p.waitFor(2, TimeUnit.SECONDS); // on Windows its files can't be deleted until it's gone
    }
}

package app.unichat.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Short-lived helper programs: one that sticks can't hold Unichat past its time, and a full output pipe can't stick one. */
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
    public static Result capture(ProcessBuilder command, long seconds, java.util.function.Consumer<String> eachLine)
            throws IOException, InterruptedException {
        Process p = command.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = Thread.ofPlatform().daemon().name("process-output").start(() -> read(p, out, eachLine));
        try {
            closeInput(p);
            boolean finished = p.waitFor(seconds, TimeUnit.SECONDS);
            if (!finished) stop(p);
            reader.join(READER_GRACE_MS);
            return new Result(finished, finished ? p.exitValue() : -1, out.toString(StandardCharsets.UTF_8));
        } finally {
            if (p.isAlive()) stop(p);
        }
    }

    /** Reads to the end whatever happens to the rest: a process nobody reads from stops in write(). */
    private static void read(Process p, ByteArrayOutputStream out, java.util.function.Consumer<String> eachLine) {
        try (InputStream in = p.getInputStream()) {
            if (eachLine == null) {
                in.transferTo(out);
                return;
            }
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int b; (b = in.read()) >= 0; ) {
                out.write(b);
                if (b == '\n' || b == '\r') {
                    if (line.size() > 0) eachLine.accept(line.toString(StandardCharsets.UTF_8));
                    line.reset();
                } else {
                    line.write(b);
                }
            }
            if (line.size() > 0) eachLine.accept(line.toString(StandardCharsets.UTF_8));
        } catch (IOException ended) {
            // Killed, or its pipe closed.
        }
    }

    private static void closeInput(Process p) {
        try {
            p.getOutputStream().close();
        } catch (IOException gone) {
            // It already exited.
        }
    }

    private static void stop(Process p) throws InterruptedException {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        p.waitFor(2, TimeUnit.SECONDS); // on Windows its files can't be deleted until it's gone
    }
}

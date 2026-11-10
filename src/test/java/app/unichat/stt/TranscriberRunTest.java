package app.unichat.stt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whisper (or the person's own command) runs as a program of its own: what it says, why it failed, and stopping it. */
@DisabledOnOs(OS.WINDOWS)
class TranscriberRunTest {
    @TempDir Path dir;

    private static List<String> sh(String script) { return List.of("/bin/sh", "-c", script); }

    @Test
    void itGivesWhatTheProgramPrintsAndOnFailureItsLastErrorLine() throws Exception {
        assertEquals("hola\n", Transcriber.run(sh("echo hola; echo ruido >&2"), dir));
        IOException e = assertThrows(IOException.class, () -> Transcriber.run(sh("echo uno >&2; echo 'sin modelo' >&2; exit 2"), dir));
        assertTrue(e.getMessage().contains("sin modelo"), e.getMessage());
        assertFalse(e.getMessage().contains("uno"), e.getMessage());
    }

    @Test
    void interruptingStopsTheProgram() throws Exception {
        Path started = dir.resolve("started");
        assertEquals(0, new ProcessBuilder("mkfifo", started.toString()).inheritIO().start().waitFor());
        // It says its own process id, then never ends on its own: only being stopped ends it.
        List<String> cmd = sh("echo $$ > '" + started + "'; exec tail -f /dev/null");
        AtomicReference<Throwable> ended = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> {
            try {
                Transcriber.run(cmd, dir);
            } catch (Throwable e) {
                ended.set(e);
            }
        });
        long pid = Long.parseLong(Files.readString(started).strip());
        t.interrupt();
        t.join(); // lesson-ok: test-future-join (a thread)
        assertInstanceOf(InterruptedException.class, ended.get());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "whisper was left running");
    }
}

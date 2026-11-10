package app.unichat.support;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ProcessesTest {
    @TempDir Path dir;

    @BeforeAll
    static void needsAShell() { assumeFalse(Os.windows(), "these use sh"); }

    private static List<String> sh(String script) { return List.of("/bin/sh", "-c", script); }

    /** No limit of the test's own: a program that should end does, and JUnit's global timeout is the only one. */
    private static final long NO_LIMIT = Long.MAX_VALUE;
    /** A program that never ends on its own: only being stopped ends it. */
    private static final String FOREVER = "exec tail -f /dev/null";

    @Test
    void capturesStandardOutputWithoutTheErrors() throws Exception {
        Processes.Result r = Processes.capture(sh("echo respuesta; echo ruido >&2"), NO_LIMIT);
        assertTrue(r.ok());
        assertEquals("respuesta\n", r.out());
    }

    @Test
    void aFailureKeepsItsCodeAndOutput() throws Exception {
        Processes.Result r = Processes.capture(sh("echo algo; exit 3"), NO_LIMIT);
        assertTrue(r.finished());
        assertFalse(r.ok());
        assertEquals(3, r.exit());
        assertEquals("algo\n", r.out());
    }

    @Test
    void aBuilderCanMergeErrorsAndChooseTheFolder() throws Exception {
        Processes.Result r = Processes.capture(new ProcessBuilder(sh("echo uno; echo dos >&2; pwd -P"))
                .directory(dir.toFile()).redirectErrorStream(true), NO_LIMIT);
        assertEquals(List.of("uno", "dos", dir.toRealPath().toString()), r.out().lines().toList());
    }

    /** More than any pipe holds: if nobody read while it ran it would sit in write() until the timeout. */
    @Test
    void aFullPipeDoesntStickIt() throws Exception {
        Processes.Result r = Processes.capture(sh("head -c 3000000 /dev/zero"), NO_LIMIT);
        assertTrue(r.ok(), "exit " + r.exit());
        assertEquals(3_000_000, r.out().length());
    }

    @Test
    void outOfTimeItsKilled() throws Exception {
        // Never ending on its own and no time at all: returning at all means it was stopped.
        Processes.Result r = Processes.capture(sh(FOREVER), 0);
        assertFalse(r.finished());
        assertFalse(r.ok());
    }

    /** What the keychain and the login shell got wrong: reading to the end first means the timeout never comes. */
    @Test
    void aChildHoldingThePipeOpenDoesntHoldUs() throws Exception {
        // The child keeps the pipe open forever: if capture waited for the pipe, it would never return.
        Processes.Result r = Processes.capture(sh("tail -f /dev/null & echo $!; echo hecho"), NO_LIMIT);
        assertTrue(r.ok());
        List<String> lines = r.out().lines().toList();
        assertEquals("hecho", lines.get(1));
        ProcessHandle.of(Long.parseLong(lines.get(0))).ifPresent(ProcessHandle::destroyForcibly);
    }

    @Test
    void itGetsAnEmptyInput() throws Exception {
        assertEquals("fin\n", Processes.capture(sh("cat; echo fin"), NO_LIMIT).out());
    }

    @Test
    void quietRunsInItsFolderAndKeepsNothing() throws Exception {
        Processes.Result r = Processes.quiet(sh("echo x; echo y > hecho.txt; exit 2"), dir, NO_LIMIT);
        assertEquals(2, r.exit());
        assertEquals("", r.out());
        assertTrue(Files.exists(dir.resolve("hecho.txt")));
        assertFalse(Processes.quiet(sh(FOREVER), null, 0).finished());
    }

    @Test
    void eachLineIsHandedOverAsItComesAndStillKept() throws Exception {
        List<String> lines = new java.util.ArrayList<>();
        Processes.Result r = Processes.capture(new ProcessBuilder(sh("printf 'uno\\n[download]  45.3%%\\rdos'")), NO_LIMIT, lines::add);
        assertEquals(List.of("uno", "[download]  45.3%", "dos"), lines, "a carriage return ends a line too (progress bars)");
        assertEquals("uno\n[download]  45.3%\rdos", r.out());
    }
}

package app.unichat.support;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Timeout(30)
class ProcessesTest {
    @TempDir Path dir;

    @BeforeAll
    static void needsAShell() { assumeFalse(Os.windows(), "these use sh"); }

    private static List<String> sh(String script) { return List.of("/bin/sh", "-c", script); }

    @Test
    void capturesStandardOutputWithoutTheErrors() throws Exception {
        Processes.Result r = Processes.capture(sh("echo respuesta; echo ruido >&2"), 10);
        assertTrue(r.ok());
        assertEquals("respuesta\n", r.out());
    }

    @Test
    void aFailureKeepsItsCodeAndOutput() throws Exception {
        Processes.Result r = Processes.capture(sh("echo algo; exit 3"), 10);
        assertTrue(r.finished());
        assertFalse(r.ok());
        assertEquals(3, r.exit());
        assertEquals("algo\n", r.out());
    }

    @Test
    void aBuilderCanMergeErrorsAndChooseTheFolder() throws Exception {
        Processes.Result r = Processes.capture(new ProcessBuilder(sh("echo uno; echo dos >&2; pwd -P"))
                .directory(dir.toFile()).redirectErrorStream(true), 10);
        assertEquals(List.of("uno", "dos", dir.toRealPath().toString()), r.out().lines().toList());
    }

    /** More than any pipe holds: if nobody read while it ran it would sit in write() until the timeout. */
    @Test
    void aFullPipeDoesntStickIt() throws Exception {
        Processes.Result r = Processes.capture(sh("head -c 3000000 /dev/zero"), 20);
        assertTrue(r.ok(), "exit " + r.exit());
        assertEquals(3_000_000, r.out().length());
    }

    @Test
    void outOfTimeItsKilled() throws Exception {
        long start = System.nanoTime();
        Processes.Result r = Processes.capture(sh("echo antes; exec sleep 25"), 1);
        assertFalse(r.finished());
        assertFalse(r.ok());
        assertEquals("antes\n", r.out());
        assertTrue(System.nanoTime() - start < 10_000_000_000L, "it didn't wait for the program");
    }

    /** What the keychain and the login shell got wrong: reading to the end first means the timeout never comes. */
    @Test
    void aChildHoldingThePipeOpenDoesntHoldUs() throws Exception {
        long start = System.nanoTime();
        Processes.Result r = Processes.capture(sh("sleep 20 & echo hecho"), 10);
        assertTrue(r.ok());
        assertEquals("hecho\n", r.out());
        assertTrue(System.nanoTime() - start < 8_000_000_000L, "it should end with the program");
    }

    @Test
    void itGetsAnEmptyInput() throws Exception {
        assertEquals("fin\n", Processes.capture(sh("cat; echo fin"), 5).out());
    }

    @Test
    void quietRunsInItsFolderAndKeepsNothing() throws Exception {
        Processes.Result r = Processes.quiet(sh("echo x; echo y > hecho.txt; exit 2"), dir, 10);
        assertEquals(2, r.exit());
        assertEquals("", r.out());
        assertTrue(Files.exists(dir.resolve("hecho.txt")));
        assertFalse(Processes.quiet(sh("exec sleep 20"), null, 1).finished());
    }
}

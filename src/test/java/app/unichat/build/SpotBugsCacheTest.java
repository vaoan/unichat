package app.unichat.build;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SpotBugs runs again only when what it reads changed since the last run that passed: the classes, the tests' classes,
 * the pom, the filter, the libraries and the JDK. A run that failed leaves nothing to skip by.
 */
class SpotBugsCacheTest {
    @TempDir Path project;
    private Path cache;
    private int run;
    private final List<String> classpath = List.of("/repo/jackson-databind-2.17.2.jar", "/repo/jsoup-1.18.3.jar");

    @BeforeEach
    void aProject() throws IOException {
        cache = project.resolve("target/spotbugs-cache");
        write("target/classes/app/A.class", "a");
        write("target/test-classes/app/ATest.class", "t");
        write("pom.xml", "<project/>");
        write("spotbugs-exclude.xml", "<FindBugsFilter/>");
    }

    private void write(String path, String text) throws IOException {
        Path f = project.resolve(path);
        Files.createDirectories(app.unichat.support.PathParts.parent(f));
        Files.writeString(f, text);
    }

    /** A whole build: the check, then SpotBugs passing (or not), then the mark of the pass. */
    private boolean build(boolean passes) throws IOException {
        boolean skipped = check(classpath);
        if (passes) SpotBugsCache.passed(cache);
        return skipped;
    }

    private boolean check(List<String> cp) throws IOException {
        String id = "run" + ++run;
        boolean skip = SpotBugsCache.check(project, cache, cp, id);
        assertEquals("spotbugs.skip=" + skip + "\n", Files.readString(cache.resolve(SpotBugsCache.decision(id))), "what Maven reads");
        return skip;
    }

    @Test
    void theFirstRunAnalyses() throws IOException {
        assertFalse(build(true));
    }

    @Test
    void aRepeatOfAPassingRunWithNothingChangedSkips() throws IOException {
        build(true);
        assertTrue(build(true));
        assertTrue(build(true), "and again");
    }

    @Test
    void aChangedClassAnalysesAgain() throws IOException {
        build(true);
        write("target/classes/app/A.class", "b");
        assertFalse(build(true));
        assertTrue(build(true), "then that one passed");
    }

    @Test
    void aClassAddedOrRemovedAnalysesAgain() throws IOException {
        build(true);
        write("target/test-classes/app/BTest.class", "t");
        assertFalse(build(true));
        Files.delete(project.resolve("target/test-classes/app/BTest.class"));
        assertFalse(build(true));
    }

    @Test
    void aClassMovedAnalysesAgain() throws IOException {
        build(true);
        Files.move(project.resolve("target/classes/app/A.class"), project.resolve("target/classes/app/B.class"));
        assertFalse(build(true), "same bytes, another class");
    }

    @Test
    void aChangedFilterAnalysesAgain() throws IOException {
        build(true);
        write("spotbugs-exclude.xml", "<FindBugsFilter><Match/></FindBugsFilter>");
        assertFalse(build(true));
    }

    @Test
    void aChangedPomAnalysesAgain() throws IOException {
        build(true);
        write("pom.xml", "<project><version>2</version></project>");
        assertFalse(build(true));
    }

    @Test
    void anotherLibraryVersionAnalysesAgain() throws IOException {
        build(true);
        assertFalse(check(List.of("/repo/jackson-databind-2.18.0.jar", "/repo/jsoup-1.18.3.jar")));
    }

    @Test
    void aRunThatFailedIsNeverSkipped() throws IOException {
        build(true);
        write("target/classes/app/A.class", "a bug");
        assertFalse(build(false), "SpotBugs fails here");
        assertFalse(build(false), "nothing changed since, and it runs again");
        assertFalse(build(true), "and again, until it passes");
        assertTrue(build(true));
    }

    @Test
    void theMarkOfAPassNeedsACheckBeforeIt() throws IOException {
        SpotBugsCache.passed(cache);
        assertFalse(Files.exists(cache.resolve(SpotBugsCache.PASSED)), "no check, nothing to mark");
        assertFalse(build(true));
    }

    @Test
    void noClassesYetAnalyses() throws IOException {
        Path empty = Files.createDirectories(project.resolve("other"));
        assertFalse(SpotBugsCache.check(empty, empty.resolve("target/spotbugs-cache"), classpath, "run1"));
    }

    @Test
    void eachRunReadsOnlyItsOwnDecision() throws IOException {
        build(true);
        assertTrue(build(true));
        try (var files = Files.list(cache)) {
            assertEquals(List.of(SpotBugsCache.decision("run2")), files.map(app.unichat.support.PathParts::name)
                    .filter(n -> n.startsWith("skip-")).toList(), "an earlier run's \"skip\" is gone: a run whose check didn't run"
                    + " finds no decision, and the build stops there");
        }
    }
}

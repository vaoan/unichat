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

    /** As SpotBugs 4.10 writes it, single quotes and all. */
    private static final String CLEAN = "<BugCollection><Errors missingClasses='0' errors='0'></Errors></BugCollection>";

    /**
     * A whole build: the check; SpotBugs, which (unless skipped) writes its report and stops the build when it found
     * something; then the mark. {@code passes}: SpotBugs found nothing.
     */
    private boolean build(boolean passes) throws IOException {
        boolean skipped = check(classpath);
        if (passes) {
            if (!skipped) report(CLEAN);
            SpotBugsCache.passed(project, cache, "run" + run);
        }
        return skipped;
    }

    /** SpotBugs' report of this run: written after the check, as SpotBugs runs after it. */
    private void report(String xml) throws IOException {
        Path f = project.resolve("target/spotbugsXml.xml");
        Files.writeString(f, xml);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(cache.resolve(SpotBugsCache.INPUTS)).toMillis() + 1000));
    }

    /** A run that went on to the mark whatever SpotBugs did: what it wrote, if anything, then the mark. */
    private void buildGoingOn(String report) throws IOException {
        assertFalse(check(classpath));
        if (report != null) report(report);
        SpotBugsCache.passed(project, cache, "run" + run);
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
        SpotBugsCache.passed(project, cache, "run0");
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

    @Test
    void aRunWithSpotBugsSkippedByHandIsNeverMarked() throws IOException {
        // verify -Dspotbugs.skip=true: SpotBugs writes nothing, the build goes on to the mark.
        buildGoingOn(null);
        assertFalse(build(true), "the next plain verify analyses");
        assertTrue(build(true));
    }

    @Test
    void aReportFromAnEarlierRunIsNotThisRunsPass() throws IOException {
        build(true);
        write("target/classes/app/A.class", "b");
        assertFalse(check(classpath));
        Files.setLastModifiedTime(project.resolve("target/spotbugsXml.xml"), java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(cache.resolve(SpotBugsCache.INPUTS)).toMillis() - 1000));
        SpotBugsCache.passed(project, cache, "run" + run); // SpotBugs skipped by hand: its report is the last run's
        assertFalse(build(true), "never taken as a pass of these classes");
    }

    @Test
    void aRunThatFoundBugsWithoutFailingIsNeverMarked() throws IOException {
        // verify -Dspotbugs.failOnError=false: SpotBugs reports, the build goes on to the mark.
        buildGoingOn("<BugCollection><BugInstance type=\"X\"/><Errors errors=\"0\"></Errors></BugCollection>");
        assertFalse(build(true), "the next plain verify analyses");
    }

    @Test
    void aRunWhoseAnalysisHadErrorsIsNeverMarked() throws IOException {
        buildGoingOn("<BugCollection><Errors errors=\"2\" missingClasses=\"0\"></Errors></BugCollection>");
        assertFalse(build(true));
    }

    @Test
    void aRunThatSkippedAsCachedLeavesTheMarkAsItWas() throws IOException {
        build(true);
        String mark = Files.readString(cache.resolve(SpotBugsCache.PASSED));
        assertTrue(build(true), "skipped: no report of its own");
        assertEquals(mark, Files.readString(cache.resolve(SpotBugsCache.PASSED)));
        assertTrue(build(true));
    }
}

package app.unichat.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lessons of `.claude/lessons.tsv` hold over the whole repository, and each catches what it was written for. */
class LessonsTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();

    /** A line each lesson must catch, and the file it would be in. A new lesson comes with one. */
    private static final Map<String, String[]> CAUGHT = Map.of(
            "test-sleep", new String[] {"src/test/java/X.java", "        Thread.sleep(200);"}, // lesson-ok: test-sleep
            "test-timed-wait", new String[] {"src/test/java/X.java", "        assertTrue(done.await(5, TimeUnit.SECONDS));"}, // lesson-ok: test-timed-wait
            "static-text", new String[] {"src/main/java/X.java", "    private static final String TITLE = I18n.t(\"x\");"},
            "gif-as-image", new String[] {"src/main/java/X.java", "        view.setImage(new Image(file + \".gif\"));"},
            "css-accent-loop", new String[] {"src/main/resources/x.css", ".root { -accent: derive(-accent, 20%); }"},
            "css-label-min-width", new String[] {"src/main/resources/x.css", ".unread-badge { -fx-min-width: 20; }"});

    @Test
    void theRepositoryKeepsEveryLesson() throws IOException {
        var lessons = new Lessons(ROOT);
        var found = new ArrayList<Lessons.Finding>();
        for (String dir : new String[] {"src", "native", "packaging", "server", "tools", ".github"}) {
            if (!Files.isDirectory(ROOT.resolve(dir))) continue;
            try (Stream<Path> files = Files.walk(ROOT.resolve(dir))) {
                for (Path f : files.filter(Files::isRegularFile).toList()) found.addAll(lessons.check(f));
            }
        }
        assertTrue(found.isEmpty(), "Lessons broken (.claude/lessons.tsv):\n" + String.join("\n",
                found.stream().map(Object::toString).toList()));
    }

    @Test
    void eachLessonCatchesItsExampleAndALineMayExplainItself(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".claude"));
        Files.copy(ROOT.resolve(".claude/lessons.tsv"), root.resolve(".claude/lessons.tsv"));
        var lessons = new Lessons(root);
        for (Lessons.Lesson l : lessons.all()) {
            String[] example = CAUGHT.get(l.id());
            assertTrue(example != null, "Lesson " + l.id() + " needs an example in LessonsTest.CAUGHT");
            Path file = root.resolve(example[0]);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "first line\n" + example[1] + "\n");
            var found = lessons.check(file);
            assertEquals(1, found.size(), l.id() + " should catch: " + example[1]);
            assertEquals(2, found.get(0).line());
            Files.writeString(file, example[1] + " // lesson-ok: " + l.id() + "\n");
            assertTrue(lessons.check(file).isEmpty(), l.id() + ": lesson-ok should exempt the line");
        }
    }

    @Test
    void globsMatchFromTheRoot() {
        assertTrue(Lessons.glob("src/test/**/*.java").matcher("src/test/java/app/unichat/A.java").matches());
        assertTrue(Lessons.glob("src/**/*.java").matcher("src/A.java").matches());
        assertFalse(Lessons.glob("src/test/**/*.java").matcher("src/main/java/A.java").matches());
        assertTrue(Lessons.glob("ui/*.{css,java}").matcher("ui/a.css").matches());
        assertFalse(Lessons.glob("ui/*.java").matcher("ui/sub/a.java").matches());
    }

    @Test
    void readsTheHooksJson() {
        String json = "{\"tool_input\":{\"file_path\":\"C:\\\\repo\\\\A.java\",\"command\":\"echo \\\"hi\\\" \\u00e9\"}}";
        assertEquals("C:\\repo\\A.java", HookInput.string(json, "file_path"));
        assertEquals("echo \"hi\" é", HookInput.string(json, "command"));
        assertEquals(null, HookInput.string(json, "missing"));
    }
}

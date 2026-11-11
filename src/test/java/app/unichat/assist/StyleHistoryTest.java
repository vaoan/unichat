package app.unichat.assist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyleHistoryTest {
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);
    @TempDir Path dir;

    private List<StyleHistory.Version> kept() {
        return new StyleHistory(dir.resolve(StyleHistory.FOLDER), FIXED).versions();
    }

    private WritingStyle learnedOnce(String before, String after) {
        WritingStyle s = new WritingStyle(dir, FIXED);
        s.save(before);
        assertTrue(s.commit(before, after, 0, "!c", "$1"));
        return s;
    }

    @Test
    void learningKeepsTheVersionItReplaced() {
        WritingStyle s = learnedOnce("- viejo", "- viejo\n- nuevo");
        assertEquals(1, kept().size());
        assertEquals("- viejo", kept().get(0).text());
        assertEquals("- viejo\n- nuevo", s.text());
        WritingStyle.LastLearned last = s.lastLearned();
        assertEquals(Instant.parse("2026-11-10T12:00:00Z"), last.when());
        assertEquals(List.of("nuevo"), last.changes().added());
        assertTrue(last.changes().removed().isEmpty());
    }

    @Test
    void handEditsAreNotHistory() {
        WritingStyle s = new WritingStyle(dir, FIXED);
        s.save("- a");
        s.save("- b");
        assertNull(s.lastLearned());
        assertFalse(s.undoLast());
        assertEquals("- b", s.text());
    }

    @Test
    void theNewest20AreKept() {
        WritingStyle s = new WritingStyle(dir, FIXED); // one instant for all: each still gets its own file, in order
        for (int i = 0; i < 25; i++) assertTrue(s.commit(s.text(), "- v" + (i + 1), 0, "!c", "$" + i));
        List<StyleHistory.Version> kept = kept();
        assertEquals(StyleHistory.KEEP, kept.size());
        assertEquals("- v5", kept.get(0).text(), "the oldest went");
        assertEquals("- v24", kept.get(kept.size() - 1).text());
    }

    @Test
    void undoRestoresThePreviousAndKeepsTheUndone() {
        WritingStyle s = learnedOnce("- uno", "- uno\n- dos");
        assertTrue(s.commit(s.text(), "- tres", 0, "!c", "$2"));
        assertTrue(s.undoLast());
        assertEquals("- uno\n- dos", s.text());
        assertTrue(kept().stream().anyMatch(v -> v.undone() && v.text().equals("- tres")), "the undone one is kept: " + kept());
        assertEquals(List.of("dos"), s.lastLearned().changes().added(), "now the learning before it is the last");
        assertTrue(s.undoLast());
        assertEquals("- uno", s.text());
        assertNull(s.lastLearned());
        assertFalse(s.undoLast(), "an undone version is never undone to");
    }

    @Test
    void forgettingAlsoForgetsTheHistory() {
        WritingStyle s = learnedOnce("- uno", "- dos");
        s.forget();
        assertTrue(kept().isEmpty());
        assertNull(s.lastLearned());
    }

    @Test
    void otherFilesInTheFolderAreLeftAlone() throws Exception {
        Path folder = Files.createDirectories(dir.resolve(StyleHistory.FOLDER));
        Files.writeString(folder.resolve("notas.md"), "x");
        Files.writeString(folder.resolve("writing-style-ayer.md"), "x");
        WritingStyle s = learnedOnce("- uno", "- dos");
        s.forget();
        assertTrue(Files.exists(folder.resolve("notas.md")) && Files.exists(folder.resolve("writing-style-ayer.md")));
    }
}

package app.unichat.autosave;

import app.unichat.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedUpToTest {
    private static Message at(String id, Instant t) {
        return new Message(id, "!c", "@a", "A", false, t);
    }

    @Test
    void aChatNeverSeenStartsNowAndTwoAtTheSameTimeBothCount(@TempDir Path dir) {
        SavedUpTo marks = new SavedUpTo(dir.resolve("autosave.json"));
        assertFalse(marks.isNew("!c", at("old", Instant.now().minusSeconds(60))), "before: never");
        Instant t = Instant.now().plusSeconds(5);
        assertTrue(marks.isNew("!c", at("a", t)));
        marks.saved("!c", at("a", t));
        assertFalse(marks.isNew("!c", at("a", t)), "once");
        assertTrue(marks.isNew("!c", at("b", t)), "another with the same time");
    }

    @Test
    void whereItGotToSurvivesARestartExactly(@TempDir Path dir) {
        Instant t = Instant.parse("2030-01-02T03:04:05.123456789Z");
        SavedUpTo first = new SavedUpTo(dir.resolve("autosave.json"));
        first.startNow("!c");
        first.saved("!c", at("a", t));
        SavedUpTo again = new SavedUpTo(dir.resolve("autosave.json"));
        assertFalse(again.isNew("!c", at("a", t)), "not saved twice: the time kept to the nanosecond");
        assertTrue(again.isNew("!c", at("b", t.plusNanos(1))));
    }
}

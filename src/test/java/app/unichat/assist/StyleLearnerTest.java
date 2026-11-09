package app.unichat.assist;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyleLearnerTest {
    @TempDir Path dir;

    private static Message mine(String id, String body) {
        Message m = new Message(id, "!c", "@me", "Yo", true, Instant.parse("2026-01-02T03:04:05Z"));
        m.setType(MessageType.TEXT);
        m.setBody(body);
        return m;
    }

    private static final List<Message> CHAT = List.of(mine("$1", "hahaha sí"), mine("$2", "nos vemos"));

    @Test
    void nothingNewMeansNoCall() {
        AtomicInteger calls = new AtomicInteger();
        StyleLearner l = new StyleLearner(new WritingStyle(dir), p -> { calls.incrementAndGet(); return "- hahaha"; });
        l.learn("!c", CHAT);
        l.learn("!c", CHAT);
        assertEquals(1, calls.get());
    }

    @Test
    void aCorrectionIsLearnedAndUsedUp() {
        WritingStyle s = new WritingStyle(dir);
        s.addCorrection("jajaja", "hahaha");
        StyleLearner l = new StyleLearner(s, p -> {
            assertTrue(p.contains("«jajaja» → «hahaha»"));
            return "- hahaha";
        });
        assertEquals("- hahaha", l.learn("!c", CHAT));
        assertEquals("- hahaha", s.text());
        assertTrue(s.pending().isEmpty());
    }

    @Test
    void aFailureKeepsTheOldStyleAndTheCorrections() {
        WritingStyle s = new WritingStyle(dir);
        s.save("- viejo");
        s.addCorrection("a", "b");
        StyleLearner l = new StyleLearner(s, p -> { throw new ReplyWriter.Failure("no"); });
        assertEquals("- viejo", l.learn("!c", CHAT));
        assertEquals(1, s.pending().size());
    }

    @Test
    void anEssayIsNotSaved() {
        WritingStyle s = new WritingStyle(dir);
        s.save("- viejo");
        StyleLearner l = new StyleLearner(s, p -> "x".repeat(3001));
        assertEquals("- viejo", l.learn("!c", CHAT));
        assertEquals("- viejo", s.text());
    }

    @Test
    void oneAtATime() throws Exception {
        AtomicInteger now = new AtomicInteger(), most = new AtomicInteger();
        WritingStyle s = new WritingStyle(dir);
        StyleLearner l = new StyleLearner(s, p -> {
            most.accumulateAndGet(now.incrementAndGet(), Math::max);
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            now.decrementAndGet();
            return "- x";
        });
        Thread a = Thread.ofPlatform().start(() -> l.learn("!a", CHAT));
        Thread b = Thread.ofPlatform().start(() -> l.learn("!b", CHAT));
        a.join();
        b.join();
        assertEquals(1, most.get());
    }
}

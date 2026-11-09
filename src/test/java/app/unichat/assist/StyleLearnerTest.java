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
    void theFileStaysReadableWhileLearning() throws Exception {
        WritingStyle s = new WritingStyle(dir);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        StyleLearner l = new StyleLearner(s, p -> {
            inside.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "- x";
        });
        Thread t = Thread.ofPlatform().start(() -> l.learn("!c", CHAT));
        inside.await();
        java.util.concurrent.CompletableFuture<String> read = java.util.concurrent.CompletableFuture.supplyAsync(s::text);
        assertEquals("", read.get(2, java.util.concurrent.TimeUnit.SECONDS), "not blocked by the AI call");
        release.countDown();
        t.join();
    }

    @Test
    void aHandEditDuringLearningIsKept() {
        WritingStyle s = new WritingStyle(dir);
        s.addCorrection("a", "b");
        StyleLearner l = new StyleLearner(s, p -> {
            s.save("- a mano");
            return "- aprendido";
        });
        assertEquals("- a mano", l.learn("!c", CHAT));
        assertEquals("- a mano", s.text());
        assertEquals(1, s.pending().size(), "not used: learned again next time");
    }

    @Test
    void draftsSentAsTheyWereAreNotTheirWriting() {
        AtomicInteger calls = new AtomicInteger();
        WritingStyle s = new WritingStyle(dir);
        s.writtenByAi("hahaha sí");
        s.writtenByAi("nos vemos");
        StyleLearner l = new StyleLearner(s, p -> { calls.incrementAndGet(); return "- x"; });
        l.learn("!c", CHAT);
        assertEquals(0, calls.get(), "only the AI's texts: nothing of theirs to learn from");
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

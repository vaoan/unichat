package app.unichat.assist;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static String bullets(int n, int width) {
        StringBuilder b = new StringBuilder("# Estilo\n");
        for (int i = 0; i < n; i++) b.append("- rasgo ").append(i).append(' ').append("x".repeat(width)).append('\n');
        return b.toString();
    }

    @Test
    void aFileTooLongIsAskedToBeShortenedOnceAndThenSaved() {
        WritingStyle s = new WritingStyle(dir);
        s.save("- viejo");
        s.addCorrection("hahaha nos vemos", "nos vemos");
        List<String> asked = new java.util.ArrayList<>();
        String tooLong = bullets(38, 90); // what a real answer was: 38 traits, 3 676 characters
        StyleLearner l = new StyleLearner(s, p -> {
            asked.add(p);
            return asked.size() == 1 ? tooLong : bullets(20, 40);
        });
        assertEquals(bullets(20, 40).strip(), l.learn("!c", CHAT).strip());
        assertEquals(2, asked.size(), "one more call to shorten it, not a lost lesson");
        assertTrue(asked.get(1).contains(tooLong.strip()) && asked.get(1).contains("3000"), "it gets its own answer and the limit");
        assertTrue(s.pending().isEmpty(), "the corrections were learned");
    }

    @Test
    void stillTooLongAfterShorteningKeepsTheOldFileAndTheCorrections() {
        WritingStyle s = new WritingStyle(dir);
        s.save("- viejo");
        s.addCorrection("a", "b");
        AtomicInteger calls = new AtomicInteger();
        StyleLearner l = new StyleLearner(s, p -> { calls.incrementAndGet(); return bullets(38, 90); });
        assertEquals("- viejo", l.learn("!c", CHAT));
        assertEquals(2, calls.get(), "shortened once, not again and again");
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
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        StyleLearner l = new StyleLearner(s, p -> {
            inside.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "- x";
        });
        Thread t = Thread.ofPlatform().start(() -> l.learn("!c", CHAT));
        inside.await();
        java.util.concurrent.CompletableFuture<String> read = java.util.concurrent.CompletableFuture.supplyAsync(s::text);
        assertEquals("", read.get(), "not blocked by the AI call");
        release.countDown();
        t.join(); // a thread: interruptible, lesson-ok: test-future-join
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
    void aLearningIsKeptInTheHistory() {
        WritingStyle s = new WritingStyle(dir);
        s.save("- viejo");
        s.addCorrection("jajaja", "hahaha");
        new StyleLearner(s, p -> "- viejo\n- ríe con hahaha").learn("!c", CHAT);
        assertEquals(List.of("ríe con hahaha"), s.lastLearned().changes().added());
    }

    @Test
    void theLogLineHasCountsOnly() {
        StyleDiff d = StyleDiff.between("- secreto viejo\n- a", "- a\n- secreto nuevo\n- otro");
        String line = StyleLearner.learned(d, 3, 1);
        assertEquals("Writing style learned: 2 traits added, 1 changed/removed, from 3 corrections and 1 message", line);
        assertFalse(line.contains("secreto"), "never the text");
        assertEquals("Writing style learned: 1 trait added, 0 changed/removed, from 1 correction and 30 messages",
                StyleLearner.learned(StyleDiff.between("", "- x"), 1, 30));
    }

    @Test
    void oneAtATime() throws Exception {
        AtomicInteger now = new AtomicInteger(), most = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        WritingStyle s = new WritingStyle(dir);
        StyleLearner l = new StyleLearner(s, p -> {
            most.accumulateAndGet(now.incrementAndGet(), Math::max);
            inside.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            now.decrementAndGet();
            return "- x";
        });
        Thread a = Thread.ofPlatform().start(() -> l.learn("!a", CHAT));
        inside.await();
        Thread b = Thread.ofPlatform().start(() -> l.learn("!b", CHAT));
        // While the first is inside, the second either queues up at the lock or, wrongly, gets in too.
        while (b.getState() != Thread.State.BLOCKED && most.get() < 2 && b.isAlive()) Thread.onSpinWait();
        release.countDown();
        a.join(); // lesson-ok: test-future-join (a thread)
        b.join(); // lesson-ok: test-future-join (a thread)
        assertEquals(1, most.get());
    }

    /** The notes are asked in Unichat's language, the one the person reads them in. */
    @Test
    void theNotesAreAskedInUnichatsLanguage() {
        java.util.Locale before = app.unichat.support.I18n.locale();
        List<String> asked = new java.util.ArrayList<>();
        try {
            app.unichat.support.I18n.use("en");
            new StyleLearner(new WritingStyle(dir), p -> { asked.add(p); return "- lol"; }).learn("!c", CHAT);
        } finally {
            app.unichat.support.I18n.use(before.getLanguage());
        }
        assertTrue(asked.getFirst().contains("Write the file in English"), asked.toString());
        new StyleLearner(new WritingStyle(dir.resolve("es")), p -> { asked.add(p); return "- jajaja"; }).learn("!c", CHAT);
        assertTrue(asked.getLast().contains("Write the file in Spanish"), "the tests run in Spanish");
    }

    /** An English chat with Unichat in Spanish still asks for the notes in Spanish: the person reads them in Ajustes. */
    @Test
    void anEnglishChatWithASpanishUiStillAsksForTheNotesInSpanish() {
        List<String> asked = new java.util.ArrayList<>();
        List<Message> english = List.of(mine("$e1", "lol see you tomorrow"), mine("$e2", "omg yes"));
        new StyleLearner(new WritingStyle(dir), p -> { asked.add(p); return "- lol"; }).learn("!c", english);
        assertTrue(asked.getFirst().contains("lol see you tomorrow"), asked.toString());
        assertTrue(asked.getFirst().contains("Write the file in Spanish, this person's language, whatever language their "
                + "messages are in"), asked.toString());
    }
}

package app.unichat.assist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class StyleLearningTest {
    @TempDir Path dir;

    @Test
    void requestsWhileOneRunsBecomeOneMoreRunAfterIt() throws Exception {
        WritingStyle style = new WritingStyle(dir);
        StyleLearning learning = new StyleLearning(() -> style);
        AtomicInteger now = new AtomicInteger(), most = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> prompts = new CopyOnWriteArrayList<>();
        StyleLearner.Program program = p -> {
            most.accumulateAndGet(now.incrementAndGet(), Math::max);
            prompts.add(p);
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now.decrementAndGet();
            return "- tutea";
        };
        style.addCorrection("Claro, voy", "Claro, ya voy");
        CompletableFuture<Void> first = learning.request("!a", List.of(), program);
        inside.await();
        // While the first runs, a correction arrives (a send) and two more requests: one more run, not two.
        style.addCorrection("Nos vemos", "nos vemos!!");
        CompletableFuture<Void> second = learning.request("!a", List.of(), program);
        CompletableFuture<Void> third = learning.request("!b", List.of(), program);
        assertSame(second, third, "coalesced");
        release.countDown();
        first.get();
        third.get();
        assertEquals(2, prompts.size(), "the first run and one after it");
        assertEquals(1, most.get(), "one at a time");
        assertEquals(true, prompts.get(1).contains("nos vemos!!"), "the run after it learns what came meanwhile");
        assertEquals(List.of(), style.pending());
    }

    @Test
    void withNothingNewTheProgramIsntCalled() throws Exception {
        WritingStyle style = new WritingStyle(dir);
        AtomicInteger calls = new AtomicInteger();
        new StyleLearning(() -> style).request("!a", List.of(), p -> { calls.incrementAndGet(); return "- x"; }).get();
        assertEquals(0, calls.get());
    }

    @Test
    void aProgramThatBreaksDoesntStopLaterRuns() throws Exception {
        WritingStyle style = new WritingStyle(dir);
        StyleLearning learning = new StyleLearning(() -> style);
        style.addCorrection("a b c", "a b c d");
        learning.request("!a", List.of(), p -> { throw new IllegalStateException("roto"); }).get();
        learning.request("!a", List.of(), p -> "- aprendido").get();
        assertEquals("- aprendido", style.text());
    }
}

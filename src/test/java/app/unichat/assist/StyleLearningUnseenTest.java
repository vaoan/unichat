package app.unichat.assist;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.support.Seen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyleLearningUnseenTest {
    @TempDir Path dir;

    private final AtomicInteger started = new AtomicInteger();
    private final Executor starter = r -> {
        started.incrementAndGet();
        Thread.ofVirtual().start(r);
    };
    private Consumer<Boolean> listener;

    private StyleLearning learning() {
        StyleLearning l = new StyleLearning(() -> new WritingStyle(dir), starter);
        listener = l.listenToSeen();
        return l;
    }

    @AfterEach
    void seenAgain() {
        if (listener != null) Seen.get().removeListener(listener);
        Seen.reset(true);
    }

    private static List<Message> mine(String chat, String id, String body) {
        Message m = new Message(id, chat, "@me", "Yo", true, Instant.parse("2026-01-02T03:04:05Z"));
        m.setType(MessageType.TEXT);
        m.setBody(body);
        return List.of(m);
    }

    @Test
    void requestsWhileUnseenRunWhenSeen() throws Exception {
        StyleLearning learning = learning();
        List<String> prompts = new CopyOnWriteArrayList<>();
        Seen.reset(false);
        CompletableFuture<Void> done = learning.request("!a", mine("!a", "$1", "hola qué tal"), p -> {
            prompts.add(p);
            return "- saluda corto";
        });
        assertFalse(done.isDone(), "held while unseen");
        assertEquals(0, started.get(), "nothing starts while unseen");
        Seen.reset(true);
        done.get();
        assertEquals(1, prompts.size());
    }

    @Test
    void onlyTheLatestPerChatIsKept() throws Exception {
        StyleLearning learning = learning();
        List<String> prompts = new CopyOnWriteArrayList<>();
        StyleLearner.Program program = p -> {
            prompts.add(p);
            return "- tutea";
        };
        Seen.reset(false);
        CompletableFuture<Void> first = learning.request("!a", mine("!a", "$1", "mensaje viejo"), program);
        CompletableFuture<Void> second = learning.request("!a", mine("!a", "$2", "mensaje nuevo"), program);
        Seen.reset(true);
        first.get();
        second.get();
        assertEquals(1, prompts.size(), "one run for the chat");
        assertTrue(prompts.get(0).contains("mensaje nuevo"));
        assertFalse(prompts.get(0).contains("mensaje viejo"));
    }

    @Test
    void eachChatKeepsItsOwnLatest() throws Exception {
        StyleLearning learning = learning();
        List<String> prompts = new CopyOnWriteArrayList<>();
        StyleLearner.Program program = p -> {
            prompts.add(p);
            return "- tutea";
        };
        Seen.reset(false);
        CompletableFuture<Void> a = learning.request("!a", mine("!a", "$1", "del chat a"), program);
        CompletableFuture<Void> b = learning.request("!b", mine("!b", "$2", "del chat b"), program);
        CompletableFuture<Void> c = learning.request("!c", mine("!c", "$3", "del chat c"), program);
        Seen.reset(true);
        a.get();
        b.get();
        c.get();
        assertEquals(3, prompts.size(), "one run per chat, none dropped");
        for (String chat : List.of("del chat a", "del chat b", "del chat c")) {
            assertEquals(1, prompts.stream().filter(p -> p.contains(chat)).count(), chat);
        }
    }

    @Test
    void seenThenUnseenAgainKeepsThemHeld() throws Exception {
        StyleLearning learning = learning();
        Seen.reset(false);
        CompletableFuture<Void> done = learning.request("!a", mine("!a", "$1", "hola"), p -> "- x");
        // The release thread starts after the screen went away again: it must look again, not run.
        learning.releaseHeld();
        assertEquals(0, started.get());
        assertFalse(done.isDone());
    }

    /** Released requests wait behind the run going on, but never behind every request that comes in meanwhile. */
    @Test
    void aReleasedRequestRunsEvenWithARequestArrivingDuringEveryRun() throws Exception {
        StyleLearning learning = new StyleLearning(() -> new WritingStyle(dir), starter); // released by hand below
        List<String> order = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> asked = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> go = new CompletableFuture<>();
        AtomicInteger aRuns = new AtomicInteger();
        StyleLearner.Program[] a = new StyleLearner.Program[1];
        a[0] = p -> {
            int n = aRuns.incrementAndGet();
            order.add("a");
            try {
                if (n == 1) go.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (n < 3) asked.add(learning.request("!a", mine("!a", "$a" + n, "texto número " + n), a[0]));
            return "- tutea";
        };
        CompletableFuture<Void> first = learning.request("!a", mine("!a", "$a0", "texto número cero"), a[0]);
        Seen.reset(false);
        CompletableFuture<Void> b = learning.request("!b", mine("!b", "$b", "del chat b"), p -> {
            order.add("b");
            return "- saluda";
        });
        Seen.reset(true);
        learning.releaseHeld(); // into the backlog: a run is going on
        go.complete(null);
        first.get();
        b.get();
        asked.get(0).get();
        asked.get(1).get();
        assertEquals(List.of("a", "b", "a", "a"), List.copyOf(order), "the released one ran right after the run it waited behind");
    }
}

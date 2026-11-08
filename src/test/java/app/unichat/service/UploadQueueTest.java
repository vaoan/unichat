package app.unichat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadQueueTest {
    @TempDir Path dir;

    /** Records each send and leaves it pending until the test finishes it. */
    private static final class FakeSender {
        final List<UploadQueue.Upload> calls = new ArrayList<>();
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        final List<DoubleConsumer> progress = new ArrayList<>();

        ChatMessaging messaging() {
            return (ChatMessaging) java.lang.reflect.Proxy.newProxyInstance(ChatMessaging.class.getClassLoader(),
                    new Class<?>[]{ChatMessaging.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> "fake";
                            };
                        }
                        if (!method.getName().equals("sendFile") || args.length != 5) throw new UnsupportedOperationException();
                        calls.add(new UploadQueue.Upload((String) args[0], (Path) args[1], (String) args[2], (SendAs) args[3]));
                        progress.add((DoubleConsumer) args[4]);
                        CompletableFuture<Void> f = new CompletableFuture<>();
                        futures.add(f);
                        return f;
                    });
        }
    }

    /** Waits kept by the test: {@code ms} and what runs after. */
    private record Wait(long ms, Runnable r) {}

    /** The files exist: a file that's gone is the one case given up. */
    private UploadQueue.Upload up(String name) throws Exception {
        Path f = dir.resolve(name);
        if (!Files.exists(f)) Files.writeString(f, name);
        return new UploadQueue.Upload("!chat", f, null, SendAs.AS_IS);
    }

    private static UploadQueue now(FakeSender fake) {
        return new UploadQueue(fake.messaging(), (ms, r) -> r.run(), null);
    }

    @Test
    void sendsOneFileAtATimeInOrder() throws Exception {
        FakeSender fake = new FakeSender();
        UploadQueue q = now(fake);
        q.addAll(List.of(up("a.jpg"), up("b.jpg"), up("c.jpg")));

        assertEquals(1, fake.calls.size(), "the second waits for the first");
        assertEquals("a.jpg", fake.calls.get(0).file().getFileName().toString());
        assertEquals(1, q.status().position());

        fake.futures.get(0).complete(null);
        assertEquals(2, fake.calls.size());
        assertEquals("b.jpg", fake.calls.get(1).file().getFileName().toString());
        fake.futures.get(1).complete(null);
        fake.futures.get(2).complete(null);
        UploadQueue.Status s = q.status();
        assertTrue(s.finished());
        assertEquals(3, s.sent());
        assertEquals(1.0, s.overall());
    }

    @Test
    void aFileThatDoesNotGoUpIsTriedAgainForAsLongAsItTakesWaitingLongerEachTime() throws Exception {
        FakeSender fake = new FakeSender();
        List<Wait> waits = new ArrayList<>();
        UploadQueue q = new UploadQueue(fake.messaging(), (ms, r) -> waits.add(new Wait(ms, r)), null);
        q.addAll(List.of(up("a.jpg"), up("b.jpg")));
        for (int i = 0; i < 6; i++) {
            fake.futures.get(i).completeExceptionally(new RuntimeException("engine was closed"));
            assertEquals(i + 1, q.status().retries());
            assertEquals(0, q.status().failed(), "never given up");
            waits.remove(waits.size() - 1).r().run();
        }
        assertEquals(7, fake.calls.size());
        assertTrue(fake.calls.stream().allMatch(c -> c.file().getFileName().toString().equals("a.jpg")), "b waits for a");
        fake.futures.get(6).complete(null);
        assertEquals(1, q.status().sent());
        assertEquals(0, q.status().retries());
        assertEquals(List.of(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L),
                List.of(UploadQueue.retryDelay(1), UploadQueue.retryDelay(2), UploadQueue.retryDelay(3), UploadQueue.retryDelay(4),
                        UploadQueue.retryDelay(5), UploadQueue.retryDelay(40)));
    }

    @Test
    void cancellingWhileAFileWaitsToBeTriedAgainStopsAtOnce() throws Exception {
        FakeSender fake = new FakeSender();
        List<Wait> waits = new ArrayList<>();
        UploadQueue q = new UploadQueue(fake.messaging(), (ms, r) -> waits.add(new Wait(ms, r)), null);
        q.addAll(List.of(up("a.jpg"), up("b.jpg")));
        fake.futures.get(0).completeExceptionally(new RuntimeException("down"));
        q.cancel();
        assertTrue(q.status().finished());
        assertEquals(1, q.status().failed());
        waits.forEach(w -> w.r().run()); // the retry that was waiting does nothing now
        assertEquals(1, fake.calls.size());
    }

    @Test
    void aFileNoLongerOnDiskIsGivenUpAndTheRestGoOn() throws Exception {
        FakeSender fake = new FakeSender();
        UploadQueue q = now(fake);
        UploadQueue.Upload gone = up("gone.jpg");
        Files.delete(gone.file());
        q.addAll(List.of(gone, up("b.jpg")));
        fake.futures.get(0).completeExceptionally(new RuntimeException("unreadable"));
        assertEquals(1, q.status().failed());
        assertEquals("b.jpg", fake.calls.get(1).file().getFileName().toString());
    }

    @Test
    void theBatchIsKeptOnDiskAndPicksUpAfterARestart() throws Exception {
        Path store = dir.resolve("uploads.json");
        FakeSender first = new FakeSender();
        UploadQueue q = new UploadQueue(first.messaging(), (ms, r) -> r.run(), store);
        q.addAll(List.of(up("a.jpg"), up("b.jpg"), up("c.jpg")));
        first.futures.get(0).complete(null);
        assertTrue(Files.exists(store), "b (going up) and c (waiting) are kept");

        // Unichat closes here; the next start finds them.
        FakeSender second = new FakeSender();
        List<Wait> waits = new ArrayList<>();
        UploadQueue again = new UploadQueue(second.messaging(), (ms, r) -> waits.add(new Wait(ms, r)), store);
        assertEquals(UploadQueue.RESUME_MS, waits.get(0).ms(), "waits for the server to come up");
        waits.remove(0).r().run();
        assertEquals("b.jpg", second.calls.get(0).file().getFileName().toString());
        assertEquals(2, again.status().total());
        second.futures.get(0).complete(null);
        waits.remove(0).r().run();
        second.futures.get(1).complete(null);
        assertTrue(again.status().finished());
        assertFalse(Files.exists(store), "nothing left to keep");
    }

    @Test
    void aBatchSavedBeforeSendAsStillKnowsItsDocuments() throws Exception {
        Path store = dir.resolve("uploads.json");
        Files.writeString(store, "[{\"chatId\":\"!chat\",\"file\":\"" + up("a.jpg").file().toString().replace("\\", "\\\\")
                + "\",\"replyToId\":null,\"asDocument\":true}]");
        FakeSender fake = new FakeSender();
        new UploadQueue(fake.messaging(), (ms, r) -> r.run(), store);
        assertEquals(SendAs.DOCUMENT, fake.calls.get(0).as());
    }

    @Test
    void theWayEachFileGoesIsKept() throws Exception {
        Path store = dir.resolve("uploads.json");
        FakeSender first = new FakeSender();
        UploadQueue q = new UploadQueue(first.messaging(), (ms, r) -> r.run(), store);
        q.addAll(List.of(new UploadQueue.Upload("!chat", up("a.webp").file(), null, SendAs.PHOTO)));
        assertTrue(Files.readString(store).contains("\"as\":\"PHOTO\""));
        assertFalse(Files.readString(store).contains("asDocument"));
        FakeSender second = new FakeSender();
        new UploadQueue(second.messaging(), (ms, r) -> r.run(), store);
        assertEquals(SendAs.PHOTO, second.calls.get(0).as());
    }

    @Test
    void cancelAndANewBatchCountFromZero() throws Exception {
        FakeSender fake = new FakeSender();
        UploadQueue q = now(fake);
        q.addAll(List.of(up("a.jpg"), up("b.jpg"), up("c.jpg")));
        q.cancel();
        assertEquals(1, q.status().total(), "only the one going up remains");
        fake.futures.get(0).complete(null);
        assertTrue(q.status().finished());

        q.addAll(List.of(up("d.jpg")));
        assertEquals(1, q.status().total());
        assertEquals(0, q.status().sent());
        fake.futures.get(1).complete(null);
        q.dismiss();
        assertTrue(q.status().idle());
        assertNull(q.status().current());
    }

    @Test
    void progressCountsTowardsTheWholeBatchAndOnlyWholePercentsAreReported() throws Exception {
        FakeSender fake = new FakeSender();
        UploadQueue q = now(fake);
        int[] fired = {0};
        q.addListener(() -> fired[0]++);
        q.addAll(List.of(up("a.jpg"), up("b.jpg")));
        int before = fired[0];
        fake.progress.get(0).accept(0.5);
        fake.progress.get(0).accept(0.501);
        assertEquals(before + 1, fired[0], "the same percent again tells nobody");
        assertEquals(0.25, q.status().overall(), 1e-9);
    }

    @Test
    void theTimeLeftComesFromHowLongTheLastFilesTook() {
        assertEquals(-1, UploadQueue.eta(List.of(), 10), "unknown before the first one");
        assertEquals(30_000, UploadQueue.eta(List.of(2_000L, 4_000L), 10));
        assertEquals(0, UploadQueue.eta(List.of(2_000L), 0));
    }

    @Test
    void oneQueuePerService() {
        ChatMessaging svc = new FakeSender().messaging();
        assertSame(UploadQueue.of(svc, null), UploadQueue.of(svc));
    }
}

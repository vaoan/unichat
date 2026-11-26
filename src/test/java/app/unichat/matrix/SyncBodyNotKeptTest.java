package app.unichat.matrix;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first full /sync's body (78 MB) stayed in memory for good: with a {@code connectTimeout} on the client, the JDK's
 * pooled connection keeps its connect timer, and the timer the exchange that opened the connection, with its whole
 * response. The next long poll reuses that connection, so the body lived as long as the connection.
 */
class SyncBodyNotKeptTest {
    @Test
    @SuppressFBWarnings(value = "DM_GC", justification = "it measures what is kept after a collection (as its NOPMD says)")
    void aBodyAlreadyReadIsNotKeptByTheConnectionTheNextRequestReuses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        Set<Integer> clientPorts = ConcurrentHashMap.newKeySet();
        CountDownLatch longPollArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        byte[] big = new byte[4 * 1024 * 1024];
        Arrays.fill(big, (byte) 'x');
        server.createContext("/big", ex -> {
            clientPorts.add(ex.getRemoteAddress().getPort());
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, big.length);
            ex.getResponseBody().write(big);
            ex.close();
        });
        server.createContext("/poll", ex -> {
            clientPorts.add(ex.getRemoteAddress().getPort());
            ex.getRequestBody().readAllBytes();
            longPollArrived.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, 2);
            ex.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            ex.close();
        });
        server.start();
        try (HttpClient http = MatrixClient.newHttpClient()) {
            URI base = URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort());
            WeakReference<byte[]> body = firstResponse(http, base);
            CompletableFuture<HttpResponse<String>> poll = http.sendAsync(
                    HttpRequest.newBuilder(base.resolve("/poll")).timeout(Duration.ofMinutes(1)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean cleared = false;
            try {
                longPollArrived.await();
                for (int round = 0; round < 50 && !cleared; round++) {
                    System.gc(); // NOPMD: DoNotCallGarbageCollectionExplicitly, it measures what is kept after a collection
                    cleared = body.get() == null;
                }
            } finally {
                release.countDown(); // before the client closes: close() waits for the held poll
            }
            poll.get();
            assertEquals(1, clientPorts.size(), "the long poll must go over the connection the first body came on");
            assertTrue(cleared, "the first response's body is still held by the pooled connection");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static WeakReference<byte[]> firstResponse(HttpClient http, URI base) throws IOException, InterruptedException {
        HttpResponse<byte[]> first = http.send(
                HttpRequest.newBuilder(base.resolve("/big")).timeout(Duration.ofMinutes(1)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(4 * 1024 * 1024, first.body().length);
        return new WeakReference<>(first.body());
    }
}

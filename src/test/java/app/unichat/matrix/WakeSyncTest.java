package app.unichat.matrix;

import app.unichat.model.ConnectionState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Waking up drops the long poll left open before sleeping and catches up at once. */
class WakeSyncTest {
    private HttpServer server;
    private MatrixChatService svc;
    private final List<String> syncs = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (svc != null) svc.stop();
        if (server != null) server.stop(0);
    }

    @Test
    void wakingUpAsksAgainAtOnceAndConnectingLastsUntilTheAnswer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/sync")) {
                syncs.add(q);
                // The long poll (timeout=30000) hangs, like one left open before the computer slept.
                if (q.contains("timeout=30000")) {
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            byte[] b = (path.endsWith("/sync") ? "{\"next_batch\":\"b" + syncs.size() + "\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            } catch (java.io.IOException gone) {
                // the client dropped it
            }
        });
        server.start();
        svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@wake:example.org", "t");
        svc.start();
        until(() -> syncs.stream().anyMatch(q -> q.contains("timeout=30000")));
        int before = syncs.size();

        svc.wake();
        assertEquals(ConnectionState.CONNECTING, svc.connectionState(), "shown until the catch-up is in");
        until(() -> syncs.size() > before && syncs.get(before).contains("timeout=0"));
        until(() -> svc.connectionState() == ConnectionState.ONLINE);
        assertTrue(syncs.get(before).contains("since="), "a catch-up, not a full sync: " + syncs.get(before));
    }

    @Test
    void aServerStillStartingIsConnectingNotOffline() throws Exception {
        java.net.ServerSocket closed = new java.net.ServerSocket(0);
        int port = closed.getLocalPort();
        closed.close(); // nothing listens: refused, like the local server a second before it's up
        svc = new MatrixChatService("http://127.0.0.1:" + port, "@starting:example.org", "t");
        svc.start();
        Thread.sleep(1500); // the first refusals and their retries
        assertEquals(ConnectionState.CONNECTING, svc.connectionState());
    }

    private static void until(java.util.concurrent.Callable<Boolean> done) throws Exception {
        for (int i = 0; i < 100 && !done.call(); i++) Thread.sleep(50);
        assertTrue(done.call(), "within 5 s");
    }
}

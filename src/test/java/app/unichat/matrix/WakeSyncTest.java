package app.unichat.matrix;

import app.unichat.model.ConnectionState;
import app.unichat.service.Changes;
import app.unichat.service.ChatService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Waking up drops the long poll left open before sleeping and catches up at once. */
class WakeSyncTest {
    private HttpServer server;
    private MatrixChatService svc;
    private final List<String> syncs = new CopyOnWriteArrayList<>();
    private final Changes changes = new Changes();
    /** Holds the long poll open until the test ends. */
    private final CountDownLatch hang = new CountDownLatch(1);

    @AfterEach
    void stop() {
        hang.countDown();
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
                changes.changed();
                // The long poll (timeout=30000) hangs, like one left open before the computer slept.
                if (q.contains("timeout=30000")) {
                    try {
                        hang.await();
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
        svc.addListener(changes.listener());
        svc.start();
        changes.until(() -> syncs.stream().anyMatch(q -> q.contains("timeout=30000")));
        int before = syncs.size();

        svc.wake();
        assertEquals(ConnectionState.CONNECTING, svc.connectionState(), "shown until the catch-up is in");
        changes.until(() -> syncs.size() > before && syncs.get(before).contains("timeout=0"));
        changes.until(() -> svc.connectionState() == ConnectionState.ONLINE);
        assertTrue(syncs.get(before).contains("since="), "a catch-up, not a full sync: " + syncs.get(before));
    }

    @Test
    void anErrorInsideTheSyncDoesNotEndIt() throws Exception {
        // After waking (2026-11-13) the heap ran out in the sync thread: it ended, and "Conectando…" stayed for good.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            boolean sync = ex.getRequestURI().getPath().endsWith("/sync");
            if (sync) syncs.add(ex.getRequestURI().getRawQuery());
            boolean first = sync && syncs.size() == 1;
            byte[] b = (first ? "{\"errcode\":\"M_UNKNOWN\"}" : sync ? "{\"next_batch\":\"b\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(first ? 503 : 200, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
            changes.changed();
        });
        server.start();
        svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@oom:example.org", "t");
        svc.addListener(changes.listener());
        java.util.concurrent.atomic.AtomicBoolean thrown = new java.util.concurrent.atomic.AtomicBoolean();
        svc.pauseRetriesWith(millis -> {
            if (thrown.compareAndSet(false, true)) throw new OutOfMemoryError("Java heap space");
        });
        svc.start();
        changes.until(() -> svc.connectionState() == ConnectionState.ONLINE);
        assertTrue(thrown.get(), "the error was thrown inside the sync");
    }

    @Test
    void aServerStillStartingIsConnectingNotOffline() throws Exception {
        java.net.ServerSocket closed = new java.net.ServerSocket(0);
        int port = closed.getLocalPort();
        closed.close(); // nothing listens: refused, like the local server a second before it's up
        svc = new MatrixChatService("http://127.0.0.1:" + port, "@starting:example.org", "t");
        // Each try says SYNCING first; what follows it is what the refusal left.
        List<ConnectionState> afterRefusals = new CopyOnWriteArrayList<>();
        svc.addListener(new ChatService.Listener() {
            @Override public void connectionChanged(ConnectionState state) {
                if (state != ConnectionState.SYNCING) afterRefusals.add(state);
                changes.changed();
            }
        });
        svc.pauseRetriesWith(millis -> { }); // the order of states is the point, not the waits between tries
        svc.start();
        changes.until(() -> afterRefusals.size() >= 2);
        // Retries keep coming with no pause between them: compare a copy, not the list they're still adding to.
        assertEquals(List.of(ConnectionState.CONNECTING, ConnectionState.CONNECTING), List.copyOf(afterRefusals).subList(0, 2));
    }
}

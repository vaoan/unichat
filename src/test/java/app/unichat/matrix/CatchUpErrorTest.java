package app.unichat.matrix;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The startup catch-up from the cached sync token: a busy or restarting server (429, 502, 503) is waited out with the
 * cache kept; only an answer that says the token itself is no good falls back to the whole (multi-MB) sync.
 */
class CatchUpErrorTest {
    private static final String ME = "@yo:localhost";

    private HttpServer server;
    /** The {@code since} of each /sync asked for, "" for a full one. */
    private final BlockingQueue<String> syncs = new LinkedBlockingQueue<>();
    private final AtomicBoolean failedOnce = new AtomicBoolean();
    private final CountDownLatch testOver = new CountDownLatch(1);
    private MatrixChatService svc;

    private void fakeHomeserver(int status, String errcode) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = "{}";
            int code = 200;
            if (path.endsWith("/sync")) {
                String q = ex.getRequestURI().getQuery();
                syncs.add(q != null && q.contains("since=") ? q.replaceAll(".*since=([^&]*).*", "$1") : "");
                if (failedOnce.compareAndSet(false, true)) {
                    code = status;
                    body = "{\"errcode\":\"" + errcode + "\",\"error\":\"x\"}";
                } else {
                    try {
                        testOver.await(); // seen what it asked for: nothing more to answer
                    } catch (InterruptedException ignored) {
                        // test ending
                    }
                    body = "{\"next_batch\":\"s2\",\"rooms\":{}}";
                }
            } else if (path.contains("/pushrules")) {
                body = "{\"global\":{}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        testOver.countDown();
        if (svc != null) {
            svc.stop();
            Files.deleteIfExists(svc.syncCacheFile());
        }
        server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({
        "429, M_LIMIT_EXCEEDED, s1",
        "502, M_UNKNOWN, s1",
        "503, M_UNKNOWN, s1",
        "400, M_INVALID_PARAM, ''",
        "404, M_NOT_FOUND, ''",
    })
    void onlyARejectedTokenFallsBackToTheFullSync(int status, String errcode, String retriedFrom) throws Exception {
        fakeHomeserver(status, errcode);
        svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), ME, "token");
        svc.pauseRetriesWith(millis -> { }); // which sync comes next is the point, not the wait before it
        Path f = svc.syncCacheFile();
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"next_batch\":\"s1\",\"rooms\":{}}");
        svc.start();

        assertEquals("s1", syncs.take(), "the catch-up starts from the cached token");
        assertEquals(retriedFrom, syncs.take(), "after a " + status);
    }
}

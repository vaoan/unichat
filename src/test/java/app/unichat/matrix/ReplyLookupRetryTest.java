package app.unichat.matrix;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The message a reply answers, fetched on its own: a server that failed to answer is asked again next time. */
class ReplyLookupRetryTest {
    private HttpServer server;
    private final AtomicInteger asked = new AtomicInteger();
    private MatrixChatService svc;

    @BeforeEach
    void busyHomeserver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            if (ex.getRequestURI().getPath().contains("/event/")) asked.incrementAndGet();
            byte[] out = "{\"errcode\":\"M_UNKNOWN\",\"error\":\"bad gateway\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(502, out.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
        svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "token");
    }

    @AfterEach
    void stop() {
        svc.stop();
        server.stop(0);
    }

    @Test
    void aFailedLookupIsAskedAgain() throws Exception {
        assertNull(svc.message("!r:example.org", "$original").get(), "not had: shown as not loaded");
        assertNull(svc.message("!r:example.org", "$original").get());
        assertEquals(2, asked.get(), "the failure wasn't kept as the answer");
    }
}

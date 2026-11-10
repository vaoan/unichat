package app.unichat.matrix;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Marking a bot chat as direct rewrites the whole {@code m.direct}: written from the cache after a failed read, it
 * would drop every direct chat the cache doesn't know of. Only "there's none yet" (404) may go without the server's copy.
 */
class BotChatDirectTest {
    private static final String ME = "@me:example.org";
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;

    private BotChat bots(int getStatus, String getBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.add(ex.getRequestMethod());
            boolean get = "GET".equals(ex.getRequestMethod());
            byte[] out = (get ? getBody : "{}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(get ? getStatus : 200, out.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
        MatrixClient client = new MatrixClient("http://127.0.0.1:" + server.getAddress().getPort(), "tok");
        MatrixCore core = new MatrixCore(client, new SyncProcessor(ME), ME, "example.org", "tok");
        return new BotChat(core, new MessageSender(core, null));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aServerThatFailsToAnswerLeavesMDirectAlone() throws Exception {
        BotChat bots = bots(502, "{\"errcode\":\"M_UNKNOWN\",\"error\":\"bad gateway\"}");
        assertThrows(MatrixException.class, () -> bots.addDirect("@whatsappbot:example.org", "!bot:example.org"));
        assertEquals(List.of("GET"), calls, "nothing written over what the server has");
    }

    @Test
    void noMDirectYetIsWrittenFromTheCache() throws Exception {
        BotChat bots = bots(404, "{\"errcode\":\"M_NOT_FOUND\",\"error\":\"none\"}");
        bots.addDirect("@whatsappbot:example.org", "!bot:example.org");
        assertTrue(calls.equals(List.of("GET", "PUT")), calls.toString());
    }
}

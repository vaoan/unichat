package app.unichat.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A reply says what it answers in m.in_reply_to only: bridges pass the body on as it is, so a quote in it reaches the network. */
class ReplyContentTest {
    private static final String ROOM = "!c:example.org";
    private static final String SYNC = """
            {"next_batch":"s1","rooms":{"join":{"!c:example.org":{"timeline":{"events":[
              {"type":"m.room.message","event_id":"$1","sender":"@telegram_1:example.org","origin_server_ts":1000,
               "content":{"msgtype":"m.text","body":"la pregunta"}}]}}}}}""";

    private HttpServer server;
    private volatile String sendBody;

    @BeforeEach
    void fakeHomeserver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String out = "{}";
            if (p.endsWith("/sync")) out = ex.getRequestURI().getQuery().contains("since=") ? "{\"next_batch\":\"s1\"}" : SYNC;
            else if ("PUT".equals(ex.getRequestMethod()) && p.contains("/send/")) {
                sendBody = body;
                out = "{\"event_id\":\"$2\"}";
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theBodyIsOnlyWhatYouWrote() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "reply-test");
        svc.start();
        for (int i = 0; i < 100 && svc.messages(ROOM).isEmpty(); i++) Thread.sleep(50);
        assertTrue(!svc.messages(ROOM).isEmpty(), "the room's message arrived");

        svc.sendText(ROOM, "la respuesta", "$1").get(10, TimeUnit.SECONDS);
        svc.stop();

        JsonNode c = MatrixClient.JSON.readTree(sendBody);
        assertEquals("la respuesta", c.path("body").asText());
        assertEquals("$1", c.path("m.relates_to").path("m.in_reply_to").path("event_id").asText());
    }
}

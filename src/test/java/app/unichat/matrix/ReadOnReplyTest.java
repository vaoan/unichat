package app.unichat.matrix;

import app.unichat.service.Changes;
import app.unichat.settings.Settings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Read receipts "Al responder": opening a chat says nothing; answering it says it was read, and reacting to a message is
 * answering too. Taking a reaction away says nothing more.
 */
class ReadOnReplyTest {
    private static final String ROOM = "!c:example.org";
    private static final String SYNC = """
            {"next_batch":"s1","rooms":{"join":{"!c:example.org":{"timeline":{"events":[
              {"type":"m.room.message","event_id":"$1","sender":"@telegram_1:example.org","origin_server_ts":1000,
               "content":{"msgtype":"m.text","body":"¿vienes?"}}]}}}}}""";

    private HttpServer server;
    /** The messages each public read receipt went for, in order. */
    private final List<String> receipts = new CopyOnWriteArrayList<>();
    private Settings.ReadReceipts was;
    private MatrixChatService svc;

    @BeforeEach
    void fakeHomeserver() throws Exception {
        was = Settings.get().readReceipts();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            ex.getRequestBody().readAllBytes();
            String out = "{}";
            if (p.endsWith("/sync")) out = ex.getRequestURI().getQuery().contains("since=") ? "{\"next_batch\":\"s1\"}" : SYNC;
            else if (p.contains("/receipt/m.read/")) receipts.add(java.net.URLDecoder.decode(p.substring(p.lastIndexOf('/') + 1), StandardCharsets.UTF_8));
            else if ("PUT".equals(ex.getRequestMethod()) && p.contains("/send/")) out = "{\"event_id\":\"$mine" + System.nanoTime() + "\"}";
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
        if (svc != null) svc.stop();
        server.stop(0);
        Settings.get().setReadReceipts(was);
    }

    private void open(Settings.ReadReceipts when) throws Exception {
        Settings.get().setReadReceipts(when);
        svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "read-on-reply-" + when);
        Changes changes = new Changes();
        svc.addListener(changes.listener());
        svc.start();
        changes.until(() -> !svc.messages(ROOM).isEmpty());
    }

    @Test
    void answeringSaysItWasRead() throws Exception {
        open(Settings.ReadReceipts.ON_REPLY);
        svc.sendText(ROOM, "sí", null).get();
        assertEquals(List.of("$1"), receipts);
    }

    @Test
    void reactingIsAnsweringTooAndTakingItAwaySaysNothingMore() throws Exception {
        open(Settings.ReadReceipts.ON_REPLY);
        svc.toggleReaction(ROOM, "$1", "👍").get();
        assertEquals(List.of("$1"), receipts, "a reaction is an answer");
        svc.toggleReaction(ROOM, "$1", "👍").get();
        assertEquals(List.of("$1"), receipts);
    }

    @Test
    void neverIsNever() throws Exception {
        open(Settings.ReadReceipts.NEVER);
        svc.toggleReaction(ROOM, "$1", "👍").get();
        svc.sendText(ROOM, "sí", null).get();
        assertEquals(List.of(), receipts);
    }
}

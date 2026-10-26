package app.unichat.matrix;

import app.unichat.model.MediaLibrary;
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

/** A favourite sticker goes out as m.sticker keeping its info (bridges resend the native sticker from it). */
class SendStickerTest {
    private HttpServer server;
    private volatile String path;
    private volatile String body;

    @BeforeEach
    void fakeHomeserver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            if ("PUT".equals(ex.getRequestMethod())) {
                path = ex.getRequestURI().getPath();
                body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            }
            byte[] b = "{\"event_id\":\"$s1\"}".getBytes(StandardCharsets.UTF_8);
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
    void sendsMSticherWithTheOriginalInfo() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        JsonNode info = MatrixClient.JSON.readTree("""
                {"mimetype":"image/webp","w":512,"h":512,"fi.mau.bridged_sticker":{"network":"telegram","id":"123","emoji":"😂"}}""");
        svc.sendSticker("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/abc", "image/webp", 512, 512, info))
                .get(10, TimeUnit.SECONDS);
        assertTrue(path.contains("/send/m.sticker/"), path);
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("mxc://s/abc", sent.path("url").asText());
        assertEquals("telegram", sent.path("info").path("fi.mau.bridged_sticker").path("network").asText());
        assertEquals("image/webp", sent.path("info").path("mimetype").asText());
    }

    @Test
    void withoutInfoItStillDescribesTheImage() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        svc.sendSticker("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/x", "image/png", 160, 120, null))
                .get(10, TimeUnit.SECONDS);
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("image/png", sent.path("info").path("mimetype").asText());
        assertEquals(160, sent.path("info").path("w").asInt());
    }
}

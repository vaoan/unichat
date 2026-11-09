package app.unichat.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A GIF goes out as m.video (mp4) or m.image (gif) flagged fi.mau.gif, so bridges send it as a GIF. */
class SendGifTest {
    @TempDir Path dir;
    private HttpServer server;
    private volatile String sendPath;
    private volatile String sendBody;

    @BeforeEach
    void fakeHomeserver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String out = "{}";
            if (p.contains("/upload")) out = "{\"content_uri\":\"mxc://s/gif1\"}";
            else if ("PUT".equals(ex.getRequestMethod()) && p.contains("/send/")) {
                sendPath = p;
                sendBody = body;
                out = "{\"event_id\":\"$g\"}";
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
    void mp4GifIsAFlaggedVideo() throws Exception {
        Path f = dir.resolve("gato.mp4");
        Files.write(f, new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'});
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        svc.sendGif("!c:example.org", f, "video/mp4", 480, 360, "$original").get();
        assertTrue(sendPath.contains("/send/m.room.message/"), sendPath);
        JsonNode c = MatrixClient.JSON.readTree(sendBody);
        assertEquals("m.video", c.path("msgtype").asText());
        assertEquals("mxc://s/gif1", c.path("url").asText());
        assertTrue(c.path("info").path("fi.mau.gif").asBoolean(), sendBody);
        assertEquals("video/mp4", c.path("info").path("mimetype").asText());
        assertEquals(480, c.path("info").path("w").asInt());
        assertEquals("$original", c.path("m.relates_to").path("m.in_reply_to").path("event_id").asText(), "in reply mode it answers");
    }

    @Test
    void gifFileIsAFlaggedImage() throws Exception {
        Path f = dir.resolve("gato.gif");
        Files.write(f, "GIF89a".getBytes(StandardCharsets.US_ASCII));
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        svc.sendGif("!c:example.org", f, "image/gif", 200, 150, null).get();
        JsonNode c = MatrixClient.JSON.readTree(sendBody);
        assertEquals("m.image", c.path("msgtype").asText());
        assertTrue(c.path("info").path("fi.mau.gif").asBoolean());
    }
}

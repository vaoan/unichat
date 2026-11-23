package app.unichat.matrix;

import app.unichat.model.VoiceNote;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a voice note looks like on the wire: a bridge only sends a voice note if it's flagged as one. */
class SendVoiceTest {
    @TempDir Path dir;
    private HttpServer server;
    private volatile String sendBody;
    private volatile String uploadType;

    @BeforeEach
    void fakeHomeserver() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String out = "{}";
            if (p.contains("/upload")) {
                uploadType = ex.getRequestHeaders().getFirst("Content-Type");
                out = "{\"content_uri\":\"mxc://s/v1\"}";
            } else if ("PUT".equals(ex.getRequestMethod()) && p.contains("/send/")) {
                sendBody = body;
                out = "{\"event_id\":\"$v\"}";
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
    void itGoesFlaggedAsAVoiceNoteWithItsWaveform() throws Exception {
        Path f = Files.write(dir.resolve("nota.ogg"), new byte[]{1, 2, 3});
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        svc.sendVoice("!c:example.org", new VoiceNote(f, 2500, List.of(10, 500, 1024)), null).get();
        JsonNode c = MatrixClient.JSON.readTree(sendBody);
        assertEquals("m.audio", c.path("msgtype").asText());
        assertEquals("mxc://s/v1", c.path("url").asText());
        assertEquals("audio/ogg", c.path("info").path("mimetype").asText());
        assertEquals("audio/ogg", uploadType);
        assertEquals(2500, c.path("info").path("duration").asLong());
        assertTrue(c.has("org.matrix.msc3245.voice"), sendBody);
        assertEquals(2500, c.path("org.matrix.msc1767.audio").path("duration").asLong());
        assertEquals(1024, c.path("org.matrix.msc1767.audio").path("waveform").get(2).asInt());
    }

    @Test
    void whatISentPlaysFromDiskAtItsServerAddressToo() throws Exception {
        // Once its event comes, my message has the server's address: playing it mustn't download what this computer has.
        Path f = Files.write(dir.resolve("nota.ogg"), new byte[]{1, 2, 3});
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        svc.sendVoice("!c:example.org", new VoiceNote(f, 2500, List.of(10, 500, 1024)), null).get();
        assertEquals(f, svc.media("mxc://s/v1", 0, 0).get());
        Files.delete(f);
        assertTrue(!f.equals(svc.media("mxc://s/v1", 0, 0).get()), "a file gone from disk is downloaded");
    }

    @Test
    void aFileChangedAfterSendingIsDownloadedNotShownAsWhatWasSent() throws Exception {
        Path f = Files.write(dir.resolve("nota.ogg"), new byte[]{1, 2, 3});
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        svc.sendVoice("!c:example.org", new VoiceNote(f, 2500, List.of(10, 500, 1024)), null).get();
        java.nio.file.attribute.FileTime sent = Files.getLastModifiedTime(f);
        Files.write(f, new byte[]{9, 9, 9});
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(sent.toMillis() + 60_000));
        assertTrue(!f.equals(svc.media("mxc://s/v1", 0, 0).get()), "same size, edited since: not what was sent");
        Files.write(f, new byte[]{1, 2, 3, 4});
        Files.setLastModifiedTime(f, sent);
        assertTrue(!f.equals(svc.media("mxc://s/v1", 0, 0).get()), "same time, another size: not what was sent");
    }
}

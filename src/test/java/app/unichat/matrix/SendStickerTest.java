package app.unichat.matrix;

import app.unichat.model.MediaLibrary;
import app.unichat.model.Network;
import app.unichat.service.StickerMode;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A favourite sticker goes out as m.sticker keeping its info (bridges resend the native sticker from it). */
class SendStickerTest {
    private HttpServer server;
    private volatile String path;
    private volatile String body;
    private volatile String uploadedType;

    @BeforeEach
    void fakeHomeserver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_matrix/client/v1/media/download/", ex -> {
            java.io.ByteArrayOutputStream gif = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB), "gif", gif);
            ex.getResponseHeaders().add("Content-Type", "image/gif");
            ex.sendResponseHeaders(200, gif.size());
            try (OutputStream o = ex.getResponseBody()) {
                o.write(gif.toByteArray());
            }
        });
        server.createContext("/_matrix/media/v3/upload", ex -> {
            uploadedType = ex.getRequestHeaders().getFirst("Content-Type");
            ex.getRequestBody().readAllBytes();
            byte[] b = "{\"content_uri\":\"mxc://s/converted\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
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
        svc.sendSticker("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/abc", "image/webp", 512, 512, info), null)
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
        svc.sendSticker("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/x", "image/png", 160, 120, null), null)
                .get(10, TimeUnit.SECONDS);
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("image/png", sent.path("info").path("mimetype").asText());
        assertEquals(160, sent.path("info").path("w").asInt());
    }

    @Test
    void aGifStickerGoesToWhatsappConvertedToAFormatItTakes() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        app.unichat.model.RoomFeatures wa = app.unichat.model.RoomFeatures.parse(MatrixClient.JSON.readTree(
                "{\"id\":\"wa\",\"file\":{\"m.sticker\":{\"mime_types\":{\"image/webp\":2,\"image/png\":1}}}}"));
        JsonNode info = MatrixClient.JSON.readTree("""
                {"mimetype":"image/gif","w":256,"h":256,"size":999,"fi.mau.bridged_sticker":{"network":"telegram","id":"1"}}""");
        svc.sender().sendStickerFor("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/gif", "image/gif", 256, 256, info), wa, null)
                .get(30, TimeUnit.SECONDS);
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("mxc://s/converted", sent.path("url").asText());
        String mime = sent.path("info").path("mimetype").asText();
        assertEquals(StickerConverter.gif2webp() != null ? "image/webp" : "image/png", mime);
        assertEquals(mime, uploadedType);
        assertTrue(sent.path("info").path("fi.mau.bridged_sticker").isMissingNode(), "it's no longer that network's sticker");
        assertTrue(sent.path("info").path("size").asLong() != 999, "size describes the new file");
    }

    private JsonNode sendAs(StickerMode mode) throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        app.unichat.model.RoomFeatures wa = app.unichat.model.RoomFeatures.parse(MatrixClient.JSON.readTree(
                "{\"id\":\"wa\",\"file\":{\"m.sticker\":{\"mime_types\":{\"image/webp\":2}},\"m.video\":{\"mime_types\":{\"video/mp4\":2}}}}"));
        svc.sender().sendStickerAs("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/gif", "image/gif", 16, 16, null), wa, mode, null)
                .get(30, TimeUnit.SECONDS);
        assertTrue(path.contains("/send/m.room.message/"), path);
        assertEquals("video/mp4", uploadedType);
        return MatrixClient.JSON.readTree(body);
    }

    @Test
    void chosenGifGoesAsALoopingWhatsappGif() throws Exception {
        JsonNode sent = sendAs(StickerMode.GIF);
        assertEquals("m.video", sent.path("msgtype").asText());
        assertEquals("video/mp4", sent.path("info").path("mimetype").asText());
        assertTrue(sent.path("info").path("fi.mau.gif").asBoolean(), "WhatsApp plays it as a GIF");
    }

    @Test
    void chosenVideoGoesAsAPlainVideo() throws Exception {
        JsonNode sent = sendAs(StickerMode.VIDEO);
        assertEquals("m.video", sent.path("msgtype").asText());
        assertTrue(sent.path("info").path("fi.mau.gif").isMissingNode());
    }

    @Test
    void aBridgeOnlyReadsItsOwnDirectMedia() {
        String telegram = "mxc://media-telegram.localhost/abc";
        assertFalse(MessageSender.needsCopy(telegram, Network.TELEGRAM), "its own sticker: the bridge resends it natively");
        assertTrue(MessageSender.needsCopy(telegram, Network.DISCORD), "the error: Remote media not found");
        assertTrue(MessageSender.needsCopy(telegram, Network.MATRIX), "a native room can't reach any bridge");
        assertTrue(MessageSender.needsCopy("mxc://media-facebook.localhost/abc", Network.WHATSAPP));
        assertFalse(MessageSender.needsCopy("mxc://media-facebook.localhost/abc", Network.MESSENGER), "bridge names differ from networks");
        assertFalse(MessageSender.needsCopy("mxc://media-twitter.localhost/abc", Network.X));
        assertFalse(MessageSender.needsCopy("mxc://media-discord.localhost/abc", Network.DISCORD));
        assertFalse(MessageSender.needsCopy("mxc://localhost/abc", Network.DISCORD), "on our server: every bridge reads it");
        assertFalse(MessageSender.needsCopy("https://example.org/a.webp", Network.DISCORD));
    }

    @Test
    void aTelegramStickerGoesBackToTelegramAsItIsSoTheBridgeSendsTheOriginal() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        svc.processor().process(MatrixClient.JSON.readTree("""
                {"rooms":{"join":{"!c:example.org":{"state":{"events":[
                  {"type":"m.bridge","state_key":"x","sender":"@telegrambot:example.org","event_id":"$b","origin_server_ts":1,
                   "content":{"protocol":{"id":"telegram"},"com.beeper.room_type":"dm"}}]}}}}}"""), false);
        app.unichat.model.RoomFeatures tg = app.unichat.model.RoomFeatures.parse(MatrixClient.JSON.readTree(
                "{\"id\":\"tg\",\"file\":{\"m.sticker\":{\"mime_types\":{\"image/webp\":2,\"video/webm\":2}}}}"));
        JsonNode info = MatrixClient.JSON.readTree("""
                {"mimetype":"image/gif","w":256,"h":256,"duration":1300,
                 "fi.mau.bridged_sticker":{"network":"telegram","id":"55","pack_url":"https://t.me/addstickers/Cats"}}""");
        svc.sender().sendStickerFor("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER,
                "mxc://media-telegram.localhost/gif", "image/gif", 256, 256, info), tg, "$q").get(10, TimeUnit.SECONDS);
        assertEquals(null, uploadedType, "nothing converted: a re-encoded copy is what the bridge failed to pass on");
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("mxc://media-telegram.localhost/gif", sent.path("url").asText());
        assertEquals("55", sent.path("info").path("fi.mau.bridged_sticker").path("id").asText(), "how the bridge finds the original");
        assertEquals("$q", sent.path("m.relates_to").path("m.in_reply_to").path("event_id").asText());
    }

    @Test
    void aStickerFromAnotherBridgeGoesAsACopyOnOurServer() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        JsonNode info = MatrixClient.JSON.readTree("""
                {"mimetype":"image/webp","w":512,"h":512,"thumbnail_url":"mxc://s/t","fi.mau.bridged_sticker":{"network":"telegram","id":"123"}}""");
        svc.sender().sendStickerEvent("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/abc", "image/webp", 512, 512, info),
                app.unichat.model.RoomFeatures.UNKNOWN, true, null).get(10, TimeUnit.SECONDS);
        assertEquals("image/webp", uploadedType);
        assertTrue(path.contains("/send/m.sticker/"), path);
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertNotEquals("mxc://s/abc", sent.path("url").asText());
        assertTrue(sent.path("url").asText().startsWith("mxc://"));
        assertTrue(sent.path("info").path("fi.mau.bridged_sticker").isMissingNode(), "no longer that network's own sticker");
        assertTrue(sent.path("info").path("thumbnail_url").isMissingNode(), "the other bridge couldn't read it either");
        assertEquals(512, sent.path("info").path("w").asInt());
    }

    @Test
    void inReplyModeTheStickerAnswers() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        svc.sendSticker("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/abc", "image/webp", 512, 512, null), "$original")
                .get(10, TimeUnit.SECONDS);
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("$original", sent.path("m.relates_to").path("m.in_reply_to").path("event_id").asText());
    }
}

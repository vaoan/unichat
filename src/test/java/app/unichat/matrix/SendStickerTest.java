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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A favourite sticker goes out as m.sticker keeping its info (bridges resend the native sticker from it). */
class SendStickerTest {
    private HttpServer server;
    private volatile String path;
    private volatile String body;
    private volatile String uploadedType;
    /** What the fake server serves as any media; null = a 16 px GIF. */
    private volatile java.nio.file.Path served;

    @BeforeEach
    void fakeHomeserver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_matrix/client/v1/media/download/", ex -> {
            java.io.ByteArrayOutputStream gif = new java.io.ByteArrayOutputStream();
            if (served != null) gif.write(java.nio.file.Files.readAllBytes(served));
            else javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB), "gif", gif);
            ex.getResponseHeaders().add("Content-Type", served != null ? "video/webm" : "image/gif");
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
                .get();
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
                .get();
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
                .get();
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("mxc://s/converted", sent.path("url").asText());
        String mime = sent.path("info").path("mimetype").asText();
        assertEquals(StickerConverter.gif2webp() != null ? "image/webp" : "image/png", mime);
        assertEquals(mime, uploadedType);
        assertTrue(sent.path("info").path("fi.mau.bridged_sticker").isMissingNode(), "it's no longer that network's sticker");
        assertTrue(sent.path("info").path("size").asLong() != 999, "size describes the new file");
        assertEquals("mxc://s/gif", sent.path("info").path(MediaLibrary.ORIGINAL).path("url").asText(),
                "the copy names what it was made from: seen back in the chat, it's that sticker, not a new recent");
    }

    private JsonNode sendAs(StickerMode mode) throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        app.unichat.model.RoomFeatures wa = app.unichat.model.RoomFeatures.parse(MatrixClient.JSON.readTree(
                "{\"id\":\"wa\",\"file\":{\"m.sticker\":{\"mime_types\":{\"image/webp\":2}},\"m.video\":{\"mime_types\":{\"video/mp4\":2}}}}"));
        svc.sender().sendStickerAs("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/gif", "image/gif", 16, 16, null), wa, mode, null)
                .get();
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
    void aBridgesDirectMediaAlwaysGoesAsACopy() {
        for (String bridge : new String[] {"telegram", "discord", "whatsapp", "facebook", "twitter"}) {
            assertTrue(MessageSender.needsCopy("mxc://media-" + bridge + ".localhost/abc"), bridge
                    + ": served by its bridge alone (Discord's asks our server even for its own: Remote media not found)");
        }
        assertFalse(MessageSender.needsCopy("mxc://localhost/abc"), "on our server: every bridge reads it");
        assertFalse(MessageSender.needsCopy("https://example.org/a.webp"));
    }

    @Test
    void aTelegramGifStickerGoesBackToTelegramInAFormatItTakesStillNamingTheOriginal() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        svc.processor().process(MatrixClient.JSON.readTree("""
                {"rooms":{"join":{"!c:example.org":{"state":{"events":[
                  {"type":"m.bridge","state_key":"x","sender":"@telegrambot:example.org","event_id":"$b","origin_server_ts":1,
                   "content":{"protocol":{"id":"telegram"},"com.beeper.room_type":"dm"}}]}}}}}"""), false);
        app.unichat.model.RoomFeatures tg = app.unichat.model.RoomFeatures.parse(MatrixClient.JSON.readTree(
                "{\"id\":\"tg\",\"file\":{\"m.sticker\":{\"mime_types\":{\"image/webp\":2,\"image/png\":1,\"video/webm\":2}}}}"));
        JsonNode info = MatrixClient.JSON.readTree("""
                {"mimetype":"image/gif","w":256,"h":256,"duration":1300,
                 "fi.mau.bridged_sticker":{"network":"telegram","id":"55","pack_url":"https://t.me/addstickers/Cats"}}""");
        svc.sender().sendStickerFor("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER,
                "mxc://s/gif", "image/gif", 256, 256, info), tg, "$q").get();
        JsonNode sent = MatrixClient.JSON.readTree(body);
        String mime = sent.path("info").path("mimetype").asText();
        assertTrue(tg.acceptsMime(app.unichat.model.RoomFeatures.STICKER, mime), "the bridge refuses image/gif before looking for the original: " + mime);
        assertEquals("55", sent.path("info").path("fi.mau.bridged_sticker").path("id").asText(), "how the bridge finds the original");
        assertEquals("$q", sent.path("m.relates_to").path("m.in_reply_to").path("event_id").asText());
    }

    @Test
    void aTelegramVideoStickerGoesToDiscordAsAnAnimatedWebpThatPlaysByItself(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path ffmpeg = app.unichat.video.Ffmpeg.find();
        org.junit.jupiter.api.Assumptions.assumeTrue(ffmpeg != null, "no ffmpeg");
        org.junit.jupiter.api.Assumptions.assumeTrue(app.unichat.video.AnimatedWebp.img2webp() != null, "no img2webp");
        served = dir.resolve("s.webm");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=s=64x64:d=1:r=10",
                "-c:v", "libvpx-vp9", served.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(p.waitFor() == 0, "this ffmpeg can't make a WebM");
        MatrixChatService svc = chatOn("discord");
        JsonNode info = MatrixClient.JSON.readTree("""
                {"mimetype":"video/webm","w":64,"h":64,"fi.mau.bridged_sticker":{"network":"telegram","id":"7","pack_url":"https://t.me/addstickers/X"}}""");
        svc.sender().sendStickerFor("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/webm", "video/webm", 64, 64, info),
                app.unichat.model.RoomFeatures.UNKNOWN, null).get();
        assertEquals("image/webp", uploadedType, "Discord shows a WebM as a video to press play on; an animated WebP plays by itself");
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("mxc://s/converted", sent.path("url").asText());
        assertEquals("image/webp", sent.path("info").path("mimetype").asText());
    }

    @Test
    void aStickerLabelledWebmThatIsReallyAGifGoesAsTheGifItIs() throws Exception {
        // Telegram's bridge converts its WebM stickers (convert_from_webm) and still calls them video/webm: ffmpeg,
        // told to read a WebM, said "Invalid data found when processing input" and the label went on to Discord.
        MatrixChatService svc = chatOn("discord");
        JsonNode info = MatrixClient.JSON.readTree("""
                {"mimetype":"video/webm","w":16,"h":16,"fi.mau.bridged_sticker":{"network":"telegram","id":"8","pack_url":"https://t.me/addstickers/X"}}""");
        svc.sender().sendStickerFor("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/labelled", "video/webm", 16, 16, info),
                app.unichat.model.RoomFeatures.UNKNOWN, null).get();
        assertEquals("image/gif", uploadedType, "what the file is, not its label");
        assertEquals("image/gif", MatrixClient.JSON.readTree(body).path("info").path("mimetype").asText());
    }

    private MatrixChatService telegramChat() throws Exception {
        return chatOn("telegram");
    }

    private MatrixChatService chatOn(String protocol) throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        svc.processor().process(MatrixClient.JSON.readTree("""
                {"rooms":{"join":{"!c:example.org":{"state":{"events":[
                  {"type":"m.bridge","state_key":"x","sender":"@%sbot:example.org","event_id":"$b","origin_server_ts":1,
                   "content":{"protocol":{"id":"%s"},"com.beeper.room_type":"dm"}}]}}}}}""".formatted(protocol, protocol)), false);
        return svc;
    }

    /** Telegram's own capabilities: WebP "fully supported", yet its bridge passes a WebP through an ffmpeg that may lack WebP. */
    private static final String TELEGRAM_FEATURES =
            "{\"id\":\"tg\",\"file\":{\"m.sticker\":{\"mime_types\":{\"image/webp\":2,\"video/webm\":2,\"image/png\":1,\"image/jpeg\":1}}}}";

    @Test
    void aStickerFromAnotherNetworkGoesToTelegramAsAPngItsBridgeEncodesWithoutFfmpeg() throws Exception {
        MatrixChatService svc = telegramChat();
        app.unichat.model.RoomFeatures tg = app.unichat.model.RoomFeatures.parse(MatrixClient.JSON.readTree(TELEGRAM_FEATURES));
        JsonNode info = MatrixClient.JSON.readTree("""
                {"mimetype":"image/webp","w":190,"h":190,"fi.mau.bridged_sticker":{"network":"whatsapp","id":"9"}}""");
        svc.sender().sendStickerFor("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/wa", "image/webp", 190, 190, info), tg, null)
                .get();
        assertEquals("image/png", uploadedType);
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("mxc://s/converted", sent.path("url").asText());
        assertEquals("image/png", sent.path("info").path("mimetype").asText());
        assertTrue(sent.path("info").path("fi.mau.bridged_sticker").isMissingNode());
    }

    @Test
    void aStillGifFromDiscordGoesToTelegramAsAPng() throws Exception {
        MatrixChatService svc = telegramChat();
        app.unichat.model.RoomFeatures tg = app.unichat.model.RoomFeatures.parse(MatrixClient.JSON.readTree(TELEGRAM_FEATURES));
        svc.sender().sendStickerFor("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER,
                "mxc://s/discord", "image/gif", 160, 160, null), tg, null).get();
        assertEquals("image/png", uploadedType, "one frame: nothing to animate");
        assertEquals("image/png", MatrixClient.JSON.readTree(body).path("info").path("mimetype").asText());
    }

    @Test
    void aStickerFromAnotherBridgeGoesAsACopyOnOurServer() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        JsonNode info = MatrixClient.JSON.readTree("""
                {"mimetype":"image/webp","w":512,"h":512,"thumbnail_url":"mxc://s/t","fi.mau.bridged_sticker":{"network":"telegram","id":"123"}}""");
        svc.sender().sendStickerEvent("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/abc", "image/webp", 512, 512, info),
                app.unichat.model.RoomFeatures.UNKNOWN, true, null).get();
        assertEquals("image/gif", uploadedType, "the fake server's file is a GIF: it goes as what it is, whatever the label");
        assertTrue(path.contains("/send/m.sticker/"), path);
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertNotEquals("mxc://s/abc", sent.path("url").asText());
        assertTrue(sent.path("url").asText().startsWith("mxc://"));
        assertTrue(sent.path("info").path("fi.mau.bridged_sticker").isMissingNode(), "no longer that network's own sticker");
        assertTrue(sent.path("info").path("thumbnail_url").isMissingNode(), "the other bridge couldn't read it either");
        assertEquals(512, sent.path("info").path("w").asInt());
    }

    @Test
    void aForwardedPhotoFromAnotherBridgeGoesAsACopyOnOurServer() throws Exception {
        MatrixChatService svc = chatOn("discord");
        app.unichat.model.Message m = new app.unichat.model.Message("$p", "!x:example.org", "@telegram_1:example.org", "Ana", false, java.time.Instant.EPOCH);
        m.setType(app.unichat.model.MessageType.IMAGE);
        m.setFileName("foto.gif");
        m.setMediaUrl("mxc://media-telegram.localhost/foto");
        m.setMimeType("image/gif");
        // Seen in the chat already: in the media cache, as the bridge served it (no bridge runs here).
        java.nio.file.Path media = app.unichat.settings.Settings.get().cacheDir().resolve("media");
        java.nio.file.Files.createDirectories(media);
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "gif",
                media.resolve(app.unichat.support.Hashes.sha256(MediaDownloader.cacheKey(m.mediaUrl(), 0, 0)) + ".gif").toFile());
        svc.forward("!c:example.org", m, true).get();
        assertTrue(path.contains("/send/m.room.message/"), path);
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("mxc://s/converted", sent.path("url").asText(), "Discord's bridge can't read Telegram's direct media");
        assertEquals("image/gif", uploadedType);
        assertEquals("m.image", sent.path("msgtype").asText());
    }

    /** Telegram's GIFs are silent MP4s marked as GIFs: Discord showed them as videos to press play on. */
    @Test
    void aGifThatCameAsAVideoGoesToDiscordAsAnAnimatedWebpAndToTelegramAsItIs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path ffmpeg = app.unichat.video.Ffmpeg.find();
        org.junit.jupiter.api.Assumptions.assumeTrue(ffmpeg != null, "no ffmpeg");
        org.junit.jupiter.api.Assumptions.assumeTrue(app.unichat.support.BundledTools.find("unichat.img2webp", null, "img2webp") != null, "no img2webp");
        served = dir.resolve("gif.mp4");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=s=64x64:d=1:r=10",
                "-pix_fmt", "yuv420p", "-an", served.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(p.waitFor() == 0, "this ffmpeg can't make an MP4");
        app.unichat.model.Message m = new app.unichat.model.Message("$g", "!x:example.org", "@telegram_1:example.org", "Buga", false, java.time.Instant.EPOCH);
        m.setType(app.unichat.model.MessageType.VIDEO);
        m.setFileName("gif.mp4");
        m.setMediaUrl("mxc://s/gifvideo");
        m.setMimeType("video/mp4");
        m.setSize(64, 64);
        m.setMediaInfo(MatrixClient.JSON.readTree("{\"mimetype\":\"video/mp4\",\"duration\":1000,\"fi.mau.gif\":true,\"fi.mau.loop\":true}"));

        chatOn("discord").forward("!c:example.org", m, true).get();
        assertEquals("image/webp", uploadedType, "an animated WebP, which Discord plays by itself");
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("m.image", sent.path("msgtype").asText());
        assertEquals("image/webp", sent.path("info").path("mimetype").asText());
        assertEquals("gif.webp", sent.path("filename").asText());

        uploadedType = null;
        chatOn("telegram").forward("!c:example.org", m, true).get();
        assertNull(uploadedType, "Telegram shows its own as a GIF: it goes as it is");
        assertEquals("m.video", MatrixClient.JSON.readTree(body).path("msgtype").asText());
    }

    @Test
    void aForwardedFileMustBeAtAMediaAddress() throws Exception {
        MatrixChatService svc = chatOn("telegram");
        app.unichat.model.Message m = new app.unichat.model.Message("$f", "!x:example.org", "@whatsapp_1:example.org", "Ana", false, java.time.Instant.EPOCH);
        m.setType(app.unichat.model.MessageType.FILE);
        m.setMediaUrl("file:///etc/passwd");
        assertTrue(svc.forward("!c:example.org", m, true).isCompletedExceptionally());
        assertNull(path, "nothing went");
    }

    @Test
    void aForwardedTextGoesAsItIs() throws Exception {
        MatrixChatService svc = chatOn("telegram");
        app.unichat.model.Message m = new app.unichat.model.Message("$t", "!x:example.org", "@whatsapp_1:example.org", "Ana", false, java.time.Instant.EPOCH);
        m.setBody("nos vemos");
        svc.forward("!c:example.org", m, true).get();
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("nos vemos", sent.path("body").asText());
        assertNull(uploadedType, "nothing to upload");
    }

    @Test
    void inReplyModeTheStickerAnswers() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:example.org", "t");
        svc.sendSticker("!c:example.org", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://s/abc", "image/webp", 512, 512, null), "$original")
                .get();
        JsonNode sent = MatrixClient.JSON.readTree(body);
        assertEquals("$original", sent.path("m.relates_to").path("m.in_reply_to").path("event_id").asText());
    }
}

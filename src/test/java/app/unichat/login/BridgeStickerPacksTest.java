package app.unichat.login;

import app.unichat.model.MediaLibrary;
import app.unichat.model.StickerPack;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Telegram's sticker sets through its bridge's image pack API: what it lists, and a whole set without importing it. */
class BridgeStickerPacksTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    // The shapes mautrix-go writes: a list of pack metadata, and a pack's content (images by shortcode).
    private static final String LIST = """
            [{"display_name":"Gatitos","usage":["sticker"],"fi.mau.bridged_pack":{"network":"telegram","url":"https://t.me/addstickers/gatitos"}},
             {"display_name":"Emoji raros","usage":["emoticon"],"fi.mau.bridged_pack":{"network":"telegram","url":"https://t.me/addemoji/raros"}},
             {"display_name":"Sin enlace","usage":["sticker"]}]""";
    private static final String PACK = """
            {"pack":{"display_name":"Gatitos","usage":["sticker"]},
             "images":{
               "gatitos_smile":{"url":"mxc://tg.local/a1","body":"😺","info":{"mimetype":"image/webp","w":512,"h":512,"size":20000,
                 "fi.mau.bridged_sticker":{"network":"telegram","id":"11","emoji":"😺","pack_url":"https://t.me/addstickers/gatitos"}}},
               "gatitos_cry":{"url":"mxc://tg.local/a2","body":"😿","info":{"mimetype":"video/webm","w":512,"h":480}},
               "bad":{"url":"https://evil.example/x.png","body":"x"}},
             "fi.mau.telegram.stickerpack":{"id":"5","short_name":"gatitos","emoji_pack":false}}""";

    private HttpServer server;
    private final List<String> uris = new CopyOnWriteArrayList<>();

    @BeforeEach
    void fakeBridge() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            uris.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            String path = ex.getRequestURI().getPath();
            byte[] out = (path.endsWith("/v3/image_pack/list") ? LIST : PACK).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(out); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private BridgeStickerPacks packs() {
        return new BridgeStickerPacks("http://127.0.0.1:" + server.getAddress().getPort() + "/_matrix/provision", "tok", "@me:local", "login1");
    }

    @Test
    void listsOnlyStickerSetsWithALink() throws Exception {
        assertEquals(List.of(new StickerPack.Ref("Gatitos", "https://t.me/addstickers/gatitos")), packs().list().orElseThrow());
        assertTrue(uris.get(0).startsWith("GET /_matrix/provision/v3/image_pack/list?user_id=%40me%3Alocal&login_id=login1"), uris.get(0));
    }

    @Test
    void aWholeSetIsPreviewedNeverImported() throws Exception {
        StickerPack p = packs().pack("https://t.me/addstickers/gatitos");
        assertEquals("GET /_matrix/provision/v3/image_pack/import?user_id=%40me%3Alocal&login_id=login1"
                + "&pack_url=https%3A%2F%2Ft.me%2Faddstickers%2Fgatitos", uris.get(0), "GET previews; POST would add it to a space");
        assertEquals("Gatitos", p.title());
        assertEquals(List.of("mxc://tg.local/a1", "mxc://tg.local/a2"), p.stickers().stream().map(MediaLibrary.Item::url).toList(),
                "in the bridge's order; an address that isn't the server's is left out");
        MediaLibrary.Item first = p.stickers().get(0);
        assertEquals(MediaLibrary.Kind.STICKER, first.kind());
        assertEquals("image/webp", first.mime());
        assertEquals(512, first.width());
        assertEquals("11", first.info().path("fi.mau.bridged_sticker").path("id").asText(), "kept, so Telegram gets its own sticker");
    }

    @Test
    void readsWhatItCachedAsTheBridgeWroteIt() throws Exception {
        assertEquals(packs().pack("https://t.me/addstickers/gatitos"),
                BridgeStickerPacks.pack(JSON.readTree(PACK), "https://t.me/addstickers/gatitos"));
    }
}

package app.unichat.gif;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GifSearchTest {
    private HttpServer server;
    private final List<String> queries = new CopyOnWriteArrayList<>();
    /** The slow provider answers only once the test is over. */
    private final CountDownLatch slow = new CountDownLatch(1);

    private static final String GIPHY = """
            {"data":[{"id":"g1","title":"Cat GIF","images":{
              "fixed_width":{"url":"https://media.giphy.com/g1/200w.gif","mp4":"https://media.giphy.com/g1/200w.mp4","width":"200","height":"150"},
              "original":{"url":"https://media.giphy.com/g1/giphy.gif","mp4":"https://media.giphy.com/g1/giphy.mp4","width":"480","height":"360"}}},
             {"id":"g2","title":"Dog","images":{
              "fixed_width":{"url":"https://media.giphy.com/g2/200w.gif","width":"200","height":"200"},
              "original":{"url":"https://media.giphy.com/g2/giphy.gif","width":"400","height":"400"}}}]}""";

    private static final String KLIPY = """
            {"result":true,"data":{"data":[{"id":101,"slug":"hello-hi-662","title":"Hello","file":{
               "hd":{"gif":{"url":"https://static.klipy.com/h.gif","width":498,"height":280},"mp4":{"url":"https://static.klipy.com/h.mp4","width":498,"height":280}},
               "sm":{"gif":{"url":"https://static.klipy.com/s.gif","width":220,"height":124}}}},
              {"id":102,"type":"ad","title":"Ad"}],
             "current_page":1,"per_page":24,"has_next":true}}""";

    @BeforeEach
    void fakeProviders() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String uri = ex.getRequestURI().toString();
            queries.add(uri);
            String body;
            int status = 200;
            if (uri.startsWith("/giphy/v1/gifs/search")) body = GIPHY;
            else if (uri.startsWith("/klipy/api/v1/KEY2/gifs/search")) body = KLIPY;
            else if (uri.startsWith("/broken")) { status = 500; body = "{}"; }
            else if (uri.startsWith("/slow")) {
                try { slow.await(); } catch (InterruptedException ignored) { }
                body = GIPHY;
            } else { status = 404; body = "{}"; }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        slow.countDown();
        server.stop(0);
    }

    private String base(String p) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + p;
    }

    @Test
    void giphyResultsAreParsed() throws Exception {
        List<Gif> g = new GiphyProvider("KEY1", base("giphy")).search("gato feliz", 10);
        assertEquals(2, g.size());
        Gif cat = g.get(0);
        assertEquals("giphy", cat.provider());
        assertEquals("https://media.giphy.com/g1/200w.gif", cat.previewUrl());
        assertEquals("https://media.giphy.com/g1/giphy.mp4", cat.mp4Url());
        assertEquals(480, cat.width());
        assertEquals("", g.get(1).mp4Url() == null ? "" : g.get(1).mp4Url(), "no mp4 → GIF only");
    }

    @Test
    void klipyResultsAreParsedAndAdsDropped() throws Exception {
        List<Gif> g = new KlipyProvider("KEY2", base("klipy")).search("hola", 10);
        assertEquals(1, g.size(), "the ad item is not a GIF");
        assertEquals("https://static.klipy.com/s.gif", g.get(0).previewUrl());
        assertEquals("https://static.klipy.com/h.mp4", g.get(0).mp4Url());
        assertEquals(498, g.get(0).width());
    }

    @Test
    void onlyTheSearchTextAndKeyAreSent() throws Exception {
        new GiphyProvider("KEY1", base("giphy")).search("gato feliz", 10);
        new KlipyProvider("KEY2", base("klipy")).search("hola", 10);
        for (String q : queries) {
            assertFalse(q.contains("customer_id"), q);
            assertFalse(q.contains("@"), "no Matrix ids: " + q);
        }
        assertTrue(queries.get(0).contains("q=gato+feliz") || queries.get(0).contains("q=gato%20feliz"), queries.get(0));
    }

    @Test
    void providersAreInterleaved() {
        GifSearch s = new GifSearch(List.of(new GiphyProvider("KEY1", base("giphy")), new KlipyProvider("KEY2", base("klipy"))));
        List<Gif> g = s.search("hola", 10);
        assertEquals(List.of("giphy", "klipy", "giphy"), g.stream().map(Gif::provider).toList());
    }

    @Test
    void aFailingOrSlowProviderDoesNotHideTheOthers() {
        GifSearch s = new GifSearch(List.of(new GiphyProvider("K", base("broken")), new GiphyProvider("K", base("slow")),
                new KlipyProvider("KEY2", base("klipy"))));
        // The slow one never answers while the test runs, so getting here at all means it was cut off.
        // TODO(reactive): that takes GifSearch's fixed 5 s TIMEOUT_MS; a constructor taking the Duration would let
        // this test use a few milliseconds.
        List<Gif> g = s.search("hola", 10);
        assertEquals(1, g.size(), "Klipy's result still comes back");
    }

    @Test
    void onlyEnabledProvidersWithAKeyAreUsed() {
        java.util.Map<String, String> settings = new java.util.HashMap<>();
        assertTrue(GifProviders.active(settings::get).isEmpty(), "none by default");
        settings.put("gif.giphy.key", "abc");
        assertTrue(GifProviders.active(settings::get).isEmpty(), "a key alone doesn't turn it on");
        settings.put("gif.giphy.enabled", "true");
        settings.put("gif.klipy.enabled", "true"); // no key: skipped
        assertEquals(List.of("giphy"), GifProviders.active(settings::get).stream().map(GifProvider::id).toList());
        assertEquals(List.of("giphy", "klipy"), GifProviders.known().stream().map(GifProviders.Known::id).toList());
    }
}

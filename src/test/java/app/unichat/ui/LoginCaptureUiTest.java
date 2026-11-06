package app.unichat.ui;

import app.unichat.login.BridgeLogin;
import app.unichat.model.Network;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A real web page in the real login window: what it sends is what comes out. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class LoginCaptureUiTest {
    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
    }

    private static void page(HttpServer server, String path, String html) {
        server.createContext(path, ex -> {
            if (path.equals("/login")) ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=ajax123; Path=/; HttpOnly");
            byte[] b = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            try (var o = ex.getResponseBody()) {
                o.write(b);
            }
        });
    }

    @Test
    void itTakesTheHeadersThePageSendsAndTheHttpOnlyCookie() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        page(server, "/login", "<script>setTimeout(function () { location.href = '/feed'; }, 300);</script>");
        // Keeps making requests, as a real page does: the window's script arrives after the page has loaded.
        page(server, "/feed", """
                <script>
                setInterval(function () {
                  var x = new XMLHttpRequest();
                  x.open('GET', '/api');
                  x.setRequestHeader('X-LI-Track', '{"clientVersion":"1.2.3"}');
                  x.setRequestHeader('X-Other', 'no me interesa');
                  x.send();
                  if (window.fetch) fetch('/api', {headers: {'X-LI-Page-Instance': 'urn:li:page:d_flagship3_feed'}});
                  else { var y = new XMLHttpRequest(); y.open('GET', '/api'); y.setRequestHeader('X-LI-Page-Instance', 'urn:li:page:d_flagship3_feed'); y.send(); }
                }, 200);
                </script>""");
        page(server, "/api", "{}");
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        BridgeLogin.CookieParams params = new BridgeLogin.CookieParams(base + "/login", "UA", List.of(
                new BridgeLogin.CookieField("cookie_header", true, List.of(new BridgeLogin.Source("request_header", "Cookie", null)), "\\bJSESSIONID=[^;]+"),
                new BridgeLogin.CookieField("track", true, List.of(new BridgeLogin.Source("request_header", "X-LI-Track", null)), "clientVersion"),
                new BridgeLogin.CookieField("page", true, List.of(new BridgeLogin.Source("request_header", "X-LI-Page-Instance", null)), "urn:li:page")),
                null, ".*/feed.*");
        CompletableFuture<Map<String, String>> done = new CompletableFuture<>();
        Stage[] owner = new Stage[1];
        Platform.runLater(() -> {
            owner[0] = new Stage();
            owner[0].setScene(new Scene(new StackPane(), 200, 100));
            owner[0].show();
            LoginWebView.openCapturing(owner[0], Network.LINKEDIN, params, done::complete, () -> done.completeExceptionally(new AssertionError("cancelled")));
        });
        try {
            Map<String, String> v = done.get(30, TimeUnit.SECONDS);
            assertTrue(v.get("cookie_header").contains("JSESSIONID=ajax123"), "an HttpOnly cookie too");
            assertEquals("{\"clientVersion\":\"1.2.3\"}", v.get("track"));
            assertEquals("urn:li:page:d_flagship3_feed", v.get("page"));
            assertEquals(3, v.size(), "only what was asked for");
        } finally {
            server.stop(0);
            Platform.runLater(() -> { if (owner[0] != null) owner[0].close(); });
        }
    }
}

package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.support.Urls;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Signing in to pCloud in the browser (OAuth 2, token flow): the person signs in on pCloud's own page, with their
 * two-step verification, and pCloud sends a permission back to this computer; Unichat never sees the password.
 * pCloud gives it in the address's "#" part, which browsers don't send on, so the page at {@link #REDIRECT} reads it
 * and passes it to Unichat. Listens on 127.0.0.1 only, and only until the sign-in ends; {@code state} ties the answer
 * to this request.
 */
public final class PCloudLogin {
    /** Fixed: pCloud takes only the address registered with the app. */
    public static final int PORT = 43117;
    public static final String REDIRECT = "http://localhost:" + PORT + "/pcloud";
    /** Unichat's app at pCloud; empty until registered, and then the one a person registered can be given in Ajustes. */
    static final String CLIENT_ID = "";

    private final HttpServer server;
    private final String state = random();
    /** Where the API is for the host pCloud names; null for one that isn't pCloud's (the token never goes there). */
    private final java.util.function.UnaryOperator<String> apiOf;
    private final CompletableFuture<CloudAccount> result = new CompletableFuture<>();

    private static String random() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** pCloud's own API hosts only ("api.pcloud.com", Europe's "eapi.pcloud.com"). */
    static String pcloudApi(String hostname) {
        return hostname != null && hostname.matches("[a-z0-9-]+\\.pcloud\\.com") ? "https://" + hostname : null;
    }

    private PCloudLogin(int port, java.util.function.UnaryOperator<String> apiOf) throws IOException {
        this.apiOf = apiOf;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/pcloud/done", this::done);
        server.createContext("/pcloud", this::page);
        server.start();
        result.whenComplete((a, e) -> server.stop(0));
    }

    /** The app's Client ID: the one given in Ajustes, else Unichat's own. */
    public static String clientId() {
        String own = Settings.get().getString("cloud.pcloudClientId", null);
        return own != null && !own.isBlank() ? own.strip() : CLIENT_ID;
    }

    /**
     * Starts signing in: {@code browser} opens pCloud's page. The account once pCloud answers; failed if the person
     * declined, or cancelled with {@link CompletableFuture#cancel}.
     */
    public static CompletableFuture<CloudAccount> start(String clientId, Consumer<URI> browser) throws IOException {
        return start(clientId, browser, PORT, PCloudLogin::pcloudApi);
    }

    static CompletableFuture<CloudAccount> start(String clientId, Consumer<URI> browser, int port,
                                                 java.util.function.UnaryOperator<String> apiOf) throws IOException {
        PCloudLogin login = new PCloudLogin(port, apiOf);
        String redirect = "http://localhost:" + login.server.getAddress().getPort() + "/pcloud";
        browser.accept(URI.create("https://my.pcloud.com/oauth2/authorize?client_id=" + Urls.enc(clientId)
                + "&response_type=token&redirect_uri=" + Urls.enc(redirect) + "&state=" + login.state));
        return login.result;
    }

    /** The page pCloud sends the browser to: it passes the "#" part on to {@link #done}. */
    private void page(HttpExchange x) throws IOException {
        String html = """
                <!doctype html><meta charset="utf-8"><title>Unichat</title>
                <body style="font-family: system-ui, sans-serif; padding: 3em; text-align: center">
                <p id="m">%s</p>
                <script>
                fetch('/pcloud/done?' + location.hash.substring(1)).then(r => r.text())
                  .then(t => { document.getElementById('m').textContent = t; });
                </script>
                """.formatted(I18n.t("cloud.pcloud.finishing"));
        reply(x, 200, "text/html; charset=utf-8", html);
    }

    private void done(HttpExchange x) throws IOException {
        Map<String, String> q = query(x.getRequestURI().getRawQuery());
        if (!state.equals(q.get("state"))) {
            reply(x, 400, "text/plain; charset=utf-8", I18n.t("cloud.pcloud.notOurs"));
            return; // someone else's answer: keep waiting for ours
        }
        String api = apiOf.apply(q.get("hostname"));
        if (q.get("access_token") == null || api == null) {
            reply(x, 200, "text/plain; charset=utf-8", I18n.t("cloud.pcloud.declined"));
            result.completeExceptionally(new IOException(I18n.t("cloud.pcloud.declined")));
            return;
        }
        reply(x, 200, "text/plain; charset=utf-8", I18n.t("cloud.pcloud.done"));
        result.complete(new CloudAccount(CloudAccount.Service.PCLOUD, api, "pCloud", q.get("access_token")));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static void reply(HttpExchange x, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(code, b.length);
        try (var out = x.getResponseBody()) {
            out.write(b);
        }
    }
}

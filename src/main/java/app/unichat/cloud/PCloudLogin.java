package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.support.Urls;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    public static final int PORT = Loopback.PORT;
    public static final String REDIRECT = "http://localhost:" + PORT + "/pcloud";
    /** Unichat's app at pCloud; empty until registered, and then the one a person registered can be given in Ajustes. */
    static final String CLIENT_ID = "";

    private final HttpServer server;
    private final String redirect;
    private final String state = Loopback.random(16);
    /** Where the API is for the host pCloud names; null for one that isn't pCloud's (the token never goes there). */
    private final java.util.function.UnaryOperator<String> apiOf;
    private final CompletableFuture<CloudAccount> result = new CompletableFuture<>();

    /** pCloud's own API hosts only ("api.pcloud.com", Europe's "eapi.pcloud.com"). */
    static String pcloudApi(String hostname) {
        return hostname != null && hostname.matches("[a-z0-9-]+\\.pcloud\\.com") ? "https://" + hostname : null;
    }

    private PCloudLogin(int port, java.util.function.UnaryOperator<String> apiOf, Duration deadline) throws IOException {
        this.apiOf = apiOf;
        server = Loopback.server(port);
        redirect = "http://localhost:" + server.getAddress().getPort() + "/pcloud";
        server.createContext("/pcloud/done", this::done);
        server.createContext("/pcloud", this::page);
        server.start();
        Loopback.until(server, result, deadline);
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
        return start(clientId, browser, PORT, PCloudLogin::pcloudApi, Loopback.DEADLINE);
    }

    static CompletableFuture<CloudAccount> start(String clientId, Consumer<URI> browser, int port,
                                                 java.util.function.UnaryOperator<String> apiOf, Duration deadline) throws IOException {
        PCloudLogin login = new PCloudLogin(port, apiOf, deadline);
        browser.accept(URI.create("https://my.pcloud.com/oauth2/authorize?client_id=" + Urls.enc(clientId)
                + "&response_type=token&redirect_uri=" + Urls.enc(login.redirect) + "&state=" + login.state));
        return login.result;
    }

    /** The page pCloud sends the browser to: it passes the "#" part on to {@link #done}. */
    private void page(HttpExchange x) throws IOException {
        String html = """
                <!doctype html><meta charset="utf-8"><title>Unichat</title>
                <body style="font-family: system-ui, sans-serif; padding: 3em; text-align: center">
                <p id="m">%s</p>
                <script>
                const answer = location.hash.substring(1);
                history.replaceState(null, '', location.pathname); // the token doesn't stay in the browser's history
                fetch('/pcloud/done?' + answer).then(r => r.text())
                  .then(t => { document.getElementById('m').textContent = t; });
                </script>
                """.formatted(I18n.t("cloud.pcloud.finishing"));
        Loopback.reply(x, 200, "text/html; charset=utf-8", html);
    }

    private void done(HttpExchange x) throws IOException {
        Map<String, String> q = Loopback.query(x.getRequestURI().getRawQuery());
        if (!Loopback.ours(state, q.get("state"))) {
            Loopback.reply(x, 400, "text/plain; charset=utf-8", I18n.t("cloud.pcloud.notOurs"));
            return; // someone else's answer: keep waiting for ours
        }
        String api = apiOf.apply(q.get("hostname"));
        if (q.get("access_token") == null || api == null) {
            Loopback.reply(x, 200, "text/plain; charset=utf-8", I18n.t("cloud.pcloud.declined"));
            result.completeExceptionally(new IOException(I18n.t("cloud.pcloud.declined")));
            return;
        }
        Loopback.reply(x, 200, "text/plain; charset=utf-8", I18n.t("cloud.pcloud.done"));
        result.complete(new CloudAccount(CloudAccount.Service.PCLOUD, api, "pCloud", q.get("access_token")));
    }

}

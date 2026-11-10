package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.support.Urls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Signing in to Dropbox in the browser (OAuth 2 with PKCE, as Dropbox asks of desktop apps: no secret inside the app).
 * The person signs in on Dropbox's page, with their two-step verification; Dropbox sends a one-time code back to
 * {@link #REDIRECT} on this computer, which Unichat exchanges for a lasting permission (a refresh token) with the
 * secret only it made for this sign-in. Listens on 127.0.0.1 only, until the sign-in ends; {@code state} ties the answer
 * to the request.
 */
public final class DropboxLogin {
    public static final String REDIRECT = "http://localhost:" + Loopback.PORT + "/dropbox";
    /** Unichat's app at Dropbox; empty until registered, and then the one a person registered can be given in Ajustes. */
    static final String APP_KEY = "";
    static final String AUTHORIZE = "https://www.dropbox.com/oauth2/authorize";
    static final String TOKEN = "https://api.dropboxapi.com/oauth2/token";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** One for every sign-in: each client keeps its own threads and connections. */
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final HttpServer server;
    private final String state = Loopback.random(16);
    /** PKCE: made here for this sign-in; Dropbox sees only its hash until the code is exchanged. */
    private final String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(Loopback.random(32).getBytes(StandardCharsets.US_ASCII));
    private final String appKey;
    private final String redirect;
    private final String tokenUrl;
    private final CompletableFuture<CloudAccount> result = new CompletableFuture<>();

    private DropboxLogin(String appKey, int port, String tokenUrl, Duration deadline) throws IOException {
        this.appKey = appKey;
        this.tokenUrl = tokenUrl;
        server = Loopback.server(port);
        redirect = "http://localhost:" + server.getAddress().getPort() + "/dropbox";
        server.createContext("/dropbox", this::back);
        server.start();
        Loopback.until(server, result, deadline);
    }

    /** The app's key: the one given in Ajustes, else Unichat's own. */
    public static String appKey() {
        String own = Settings.get().getString("cloud.dropboxAppKey", null);
        return own != null && !own.isBlank() ? own.strip() : APP_KEY;
    }

    /**
     * Starts signing in: {@code browser} opens Dropbox's page. The account (its secret the lasting permission) once
     * Dropbox answers; failed if the person declined, or cancelled with {@link CompletableFuture#cancel}.
     */
    public static CompletableFuture<CloudAccount> start(String appKey, Consumer<URI> browser) throws IOException {
        return start(appKey, browser, Loopback.PORT, TOKEN, Loopback.DEADLINE);
    }

    static CompletableFuture<CloudAccount> start(String appKey, Consumer<URI> browser, int port, String tokenUrl,
                                                 Duration deadline) throws IOException {
        DropboxLogin login = new DropboxLogin(appKey, port, tokenUrl, deadline);
        browser.accept(URI.create(AUTHORIZE + "?client_id=" + Urls.enc(appKey) + "&response_type=code"
                + "&token_access_type=offline&code_challenge_method=S256&code_challenge=" + challenge(login.verifier)
                + "&redirect_uri=" + Urls.enc(login.redirect) + "&state=" + login.state));
        return login.result;
    }

    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void back(HttpExchange x) throws IOException {
        Map<String, String> q = Loopback.query(x.getRequestURI().getRawQuery());
        if (!Loopback.ours(state, q.get("state"))) {
            Loopback.reply(x, 400, "text/html; charset=utf-8", Loopback.page(I18n.t("cloud.pcloud.notOurs")));
            return; // someone else's answer: keep waiting for ours
        }
        String code = q.get("code");
        if (code == null) {
            Loopback.reply(x, 200, "text/html; charset=utf-8", Loopback.page(I18n.t("cloud.dropbox.declined")));
            result.completeExceptionally(new IOException(I18n.t("cloud.dropbox.declined")));
            return;
        }
        try {
            String refresh = exchange(code);
            Loopback.reply(x, 200, "text/html; charset=utf-8", Loopback.page(I18n.t("cloud.dropbox.done")));
            result.complete(new CloudAccount(CloudAccount.Service.DROPBOX, "", "Dropbox", refresh));
        } catch (IOException e) {
            Loopback.reply(x, 200, "text/html; charset=utf-8", Loopback.page(e.getMessage()));
            result.completeExceptionally(e);
        }
    }

    /** The one-time code for the lasting permission (a refresh token), with this sign-in's own secret. */
    private String exchange(String code) throws IOException {
        String form = "code=" + Urls.enc(code) + "&grant_type=authorization_code&code_verifier=" + Urls.enc(verifier)
                + "&client_id=" + Urls.enc(appKey) + "&redirect_uri=" + Urls.enc(redirect);
        HttpResponse<String> r;
        try {
            r = HTTP.send(HttpRequest.newBuilder(URI.create(tokenUrl)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        JsonNode n = JSON.readTree(r.body());
        String refresh = n.path("refresh_token").asText(null);
        if (r.statusCode() != 200 || refresh == null) {
            throw new IOException(I18n.t("cloud.dropbox.failed", n.path("error_description").asText(n.path("error").asText(r.statusCode() + ""))));
        }
        return refresh;
    }
}

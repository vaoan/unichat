package app.unichat.matrix;

import app.unichat.settings.Settings;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.matrix.MatrixLogin.userMessage;

/** Signing in: finds the homeserver of a name or URL, logs in, and restores a stored session. */
public final class MatrixLogin {
    private MatrixLogin() {}

    /** Resolves .well-known for a server name or URL, logs in with password, stores the session in Settings. */
    public static CompletableFuture<MatrixChatService> login(String homeserverOrDomain, String user, String password) {
        CompletableFuture<MatrixChatService> f = new CompletableFuture<>();
        Thread.ofVirtual().name("matrix-login").start(() -> {
            try {
                f.complete(doLogin(homeserverOrDomain, user, password));
            } catch (MatrixException e) {
                f.completeExceptionally(e);
            } catch (Exception e) {
                f.completeExceptionally(new MatrixException(userMessage(e), e));
            }
        });
        return f;
    }

    /** Session from Settings, or null if none stored. */
    public static MatrixChatService fromSettings() {
        Settings s = Settings.get();
        if (s.homeserver() == null || s.userId() == null || s.accessToken() == null) return null;
        return new MatrixChatService(s.homeserver(), s.userId(), s.accessToken());
    }

    /** Short translated message for an error coming out of this service's futures. */
    public static String userMessage(Throwable t) {
        t = Errors.unwrap(t);
        if (t instanceof MatrixException me) {
            if (me.errcode() == null) return me.getMessage();
            return switch (me.errcode()) {
                case "M_FORBIDDEN" -> I18n.t("matrix.err.forbidden");
                case "M_LIMIT_EXCEEDED" -> I18n.t("matrix.err.limit");
                case "M_UNKNOWN_TOKEN" -> I18n.t("matrix.err.unknownToken");
                case "M_USER_DEACTIVATED" -> I18n.t("matrix.err.deactivated");
                case "M_TOO_LARGE" -> I18n.t("matrix.err.tooLarge");
                case "M_NOT_FOUND" -> I18n.t("matrix.err.notFound");
                default -> I18n.t("matrix.err.server", me.getMessage());
            };
        }
        if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                || t instanceof java.net.UnknownHostException || t instanceof java.nio.channels.UnresolvedAddressException) {
            return I18n.t("matrix.err.noConnect");
        }
        if (t instanceof IOException) return I18n.t("matrix.err.network", Errors.message(t));
        return t.getMessage() == null ? I18n.t("matrix.err.unexpected") : t.getMessage();
    }

    private static MatrixChatService doLogin(String homeserverOrDomain, String user, String password) throws IOException {
        String u = user == null ? "" : user.trim();
        String hs = homeserverOrDomain == null ? "" : homeserverOrDomain.trim();
        if (hs.isEmpty() && u.startsWith("@") && u.contains(":")) hs = u.substring(u.indexOf(':') + 1);
        if (hs.isEmpty() || u.isEmpty()) throw new MatrixException(0, null, I18n.t("matrix.login.needServerUser"));
        HttpClient http = MatrixClient.newHttpClient();
        String base = resolveHomeserver(http, hs);

        ObjectNode body = MatrixClient.JSON.createObjectNode();
        body.put("type", "m.login.password");
        body.putObject("identifier").put("type", "m.id.user").put("user", u);
        body.put("password", password == null ? "" : password);
        body.put("initial_device_display_name", "Unichat");
        JsonNode resp;
        try {
            resp = new MatrixClient(http, base, null).post(CLIENT + "/login", body);
        } catch (MatrixException e) {
            String msg = switch (e.errcode() == null ? "" : e.errcode()) {
                case "M_FORBIDDEN" -> I18n.t("matrix.login.badCredentials");
                case "M_USER_DEACTIVATED" -> I18n.t("matrix.err.deactivated");
                case "M_LIMIT_EXCEEDED" -> I18n.t("matrix.err.limit");
                case "M_UNKNOWN", "M_BAD_JSON", "M_INVALID_USERNAME" -> I18n.t("matrix.login.badUser");
                default -> I18n.t("matrix.login.failed", e.getMessage());
            };
            throw new MatrixException(e.status(), e.errcode(), msg);
        }
        String token = resp.path("access_token").asText(null);
        String uid = resp.path("user_id").asText(null);
        if (token == null || uid == null) throw new MatrixException(0, null, I18n.t("matrix.login.badResponse"));
        String wkBase = resp.path("well_known").path("m.homeserver").path("base_url").asText(null);
        if (wkBase != null && !wkBase.isBlank() && safeBase(wkBase) && isHomeserver(http, wkBase)) {
            base = MatrixClient.stripSlash(wkBase);
        }
        SyncCache.drop();
        Settings.get().setMatrixSession(base, uid, token, resp.path("device_id").asText(null));
        return new MatrixChatService(base, uid, token);
    }

    /** Finds the client-server base URL for a domain or URL via .well-known, verifying /versions. */
    static String resolveHomeserver(HttpClient http, String input) {
        String in = MatrixClient.stripSlash(input);
        List<String> candidates = new ArrayList<>();
        if (in.startsWith("http://") || in.startsWith("https://")) {
            candidates.add(in);
        } else {
            candidates.add("https://" + in);
            if (isLocalHost(in)) candidates.add("http://" + in);
        }
        for (String c : candidates) {
            MatrixClient anon = new MatrixClient(http, c, null);
            try {
                String wk = anon.get("/.well-known/matrix/client").path("m.homeserver").path("base_url").asText(null);
                if (wk != null && !wk.isBlank() && safeBase(wk) && isHomeserver(http, wk)) return MatrixClient.stripSlash(wk);
            } catch (IOException | RuntimeException ignored) {
                // no .well-known: try the URL itself
            }
            if (isHomeserver(http, c)) return c;
        }
        throw new MatrixException(0, null, I18n.t("matrix.err.noConnect"));
    }

    private static boolean isHomeserver(HttpClient http, String base) {
        try {
            return new MatrixClient(http, base, null).get("/_matrix/client/versions").has("versions");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether an address a server suggests for itself (its {@code .well-known}, the login's {@code well_known}) may be
     * the session's: https, or plain http only on this machine or its network. Any other would send the token in clear.
     */
    static boolean safeBase(String url) {
        java.net.URI u;
        try {
            u = java.net.URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (u.getHost() == null) return false;
        return "https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()) && isLocalHost(u.getHost());
    }

    private static boolean isLocalHost(String hostPort) {
        String h = hostPort.split("[:/]")[0].toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")
                || h.endsWith(".local") || h.endsWith(".lan") || h.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }
}

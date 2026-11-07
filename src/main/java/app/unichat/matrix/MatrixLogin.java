package app.unichat.matrix;

import app.unichat.settings.Settings;
import app.unichat.support.Errors;
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

import static app.unichat.matrix.BridgeProtocol.*;
import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;
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

    /** Short Spanish message for an error coming out of this service's futures. */
    public static String userMessage(Throwable t) {
        t = Errors.unwrap(t);
        if (t instanceof MatrixException me) {
            if (me.errcode() == null) return me.getMessage();
            return switch (me.errcode()) {
                case "M_FORBIDDEN" -> "No tienes permiso para hacer esto";
                case "M_LIMIT_EXCEEDED" -> "Demasiados intentos, espera un momento";
                case "M_UNKNOWN_TOKEN" -> "La sesión expiró, vuelve a iniciar sesión";
                case "M_USER_DEACTIVATED" -> "La cuenta está desactivada";
                case "M_TOO_LARGE" -> "El archivo es demasiado grande para el servidor";
                case "M_NOT_FOUND" -> "No encontrado en el servidor";
                default -> "Error del servidor: " + me.getMessage();
            };
        }
        if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                || t instanceof java.net.UnknownHostException || t instanceof java.nio.channels.UnresolvedAddressException) {
            return "No se pudo conectar con el servidor";
        }
        if (t instanceof IOException) return "Error de red: " + Errors.message(t);
        return t.getMessage() == null ? "Error inesperado" : t.getMessage();
    }

    private static MatrixChatService doLogin(String homeserverOrDomain, String user, String password) throws IOException {
        String u = user == null ? "" : user.trim();
        String hs = homeserverOrDomain == null ? "" : homeserverOrDomain.trim();
        if (hs.isEmpty() && u.startsWith("@") && u.contains(":")) hs = u.substring(u.indexOf(':') + 1);
        if (hs.isEmpty() || u.isEmpty()) throw new MatrixException(0, null, "Indica el servidor y el usuario");
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
                case "M_FORBIDDEN" -> "Usuario o contraseña incorrectos";
                case "M_USER_DEACTIVATED" -> "La cuenta está desactivada";
                case "M_LIMIT_EXCEEDED" -> "Demasiados intentos, espera un momento";
                case "M_UNKNOWN", "M_BAD_JSON", "M_INVALID_USERNAME" -> "Usuario no válido";
                default -> "No se pudo iniciar sesión: " + e.getMessage();
            };
            throw new MatrixException(e.status(), e.errcode(), msg);
        }
        String token = resp.path("access_token").asText(null);
        String uid = resp.path("user_id").asText(null);
        if (token == null || uid == null) throw new MatrixException(0, null, "Respuesta de inicio de sesión no válida");
        String wkBase = resp.path("well_known").path("m.homeserver").path("base_url").asText(null);
        if (wkBase != null && !wkBase.isBlank()) base = MatrixClient.stripSlash(wkBase);
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
                if (wk != null && !wk.isBlank() && isHomeserver(http, wk)) return MatrixClient.stripSlash(wk);
            } catch (IOException | RuntimeException ignored) {
                // no .well-known: try the URL itself
            }
            if (isHomeserver(http, c)) return c;
        }
        throw new MatrixException(0, null, "No se pudo conectar con el servidor");
    }

    private static boolean isHomeserver(HttpClient http, String base) {
        try {
            return new MatrixClient(http, base, null).get("/_matrix/client/versions").has("versions");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isLocalHost(String hostPort) {
        String h = hostPort.split("[:/]")[0].toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")
                || h.endsWith(".local") || h.endsWith(".lan") || h.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }
}

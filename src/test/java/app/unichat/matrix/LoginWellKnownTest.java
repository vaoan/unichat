package app.unichat.matrix;

import app.unichat.settings.Settings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login's answer may name another address for the server ({@code well_known}). Taken as it came, a plain http one
 * (or any other) became the session's address: the token went out unencrypted from then on.
 */
class LoginWellKnownTest {
    @Test
    void anAddressTheLoginSuggestsIsTakenOnlyOverHttpsOrOnThisMachine() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = switch (path) {
                case "/_matrix/client/versions" -> "{\"versions\":[\"v1.11\"]}";
                case "/_matrix/client/v3/login" -> "{\"access_token\":\"T\",\"user_id\":\"@ana:local\",\"device_id\":\"D\","
                        + "\"well_known\":{\"m.homeserver\":{\"base_url\":\"http://attacker.invalid:8008\"}}}";
                default -> null;
            };
            byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(body == null ? 404 : 200, b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String local = "http://127.0.0.1:" + server.getAddress().getPort();
            MatrixLogin.login(local, "ana", "pw").get();
            assertEquals(local, Settings.get().homeserver());
        } finally {
            server.stop(0);
            Settings.get().clearSession();
        }
    }

    @Test
    void whatCountsAsASafeAddress() {
        assertTrue(MatrixLogin.safeBase("https://matrix.example.org"));
        assertTrue(MatrixLogin.safeBase("http://localhost:8008"));
        assertTrue(MatrixLogin.safeBase("http://192.168.1.20:8008"));
        assertFalse(MatrixLogin.safeBase("http://matrix.example.org"));
        assertFalse(MatrixLogin.safeBase("ftp://matrix.example.org"));
        assertFalse(MatrixLogin.safeBase("not a url"));
    }
}

package app.unichat.matrix;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpaceServiceTest {
    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void fakeHomeserver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()));
            String out = "{}";
            if (ex.getRequestURI().getPath().contains("/hierarchy")) {
                out = """
                    {"rooms":[{"room_id":"!s:x","name":"Servidor","room_type":"m.space"},
                              {"room_id":"!a:x","name":"anuncios"},
                              {"room_id":"!b:x","canonical_alias":"#reglas:x"},
                              {"room_id":"!c:x"}]}""";
            } else if (ex.getRequestURI().getPath().contains("/join/")) {
                out = "{\"room_id\":\"!a:x\"}";
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
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

    private MatrixChatService svc() {
        return new MatrixChatService("http://127.0.0.1:" + server.getAddress().getPort(), "@me:x", "t");
    }

    @Test
    void joinPostsToJoin() throws Exception {
        svc().joinChat("!a:x").get();
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("POST /_matrix/client/v3/join/%21a%3Ax")), calls.toString());
    }

    @Test
    void hierarchyGivesNames() throws Exception {
        Map<String, String> names = svc().spaceNames("!s:x").get();
        assertEquals(Map.of("!a:x", "anuncios", "!b:x", "#reglas:x"), names);
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("GET /_matrix/client/v1/rooms/%21s%3Ax/hierarchy") && c.contains("max_depth=1")), calls.toString());
    }
}

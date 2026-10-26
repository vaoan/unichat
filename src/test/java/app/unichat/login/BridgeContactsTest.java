package app.unichat.login;

import app.unichat.model.Contact;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeContactsTest {
    private HttpServer server;
    private final List<String> uris = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile boolean supported = true;

    @BeforeEach
    void fakeBridge() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            uris.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = 200;
            String out;
            if (!supported) {
                status = 404;
                out = "{\"errcode\":\"M_UNRECOGNIZED\",\"error\":\"Unrecognized request\"}";
            } else if (path.endsWith("/v3/contacts")) {
                out = """
                    {"contacts":[{"id":"111","name":"Ana","identifiers":["tel:+573001112233"],"mxid":"@telegram_111:x","dm_room_mxid":"!dm:x"},
                                 {"id":"222","name":"Beto","identifiers":["@beto"],"mxid":"@telegram_222:x"}]}""";
            } else if (path.endsWith("/v3/search_users")) {
                out = "{\"results\":[{\"id\":\"333\",\"name\":\"Ana María\",\"identifiers\":[],\"mxid\":\"@telegram_333:x\"}]}";
            } else if (path.contains("/v3/resolve_identifier/")) {
                out = path.endsWith("nadie") ? "{}" : "{\"id\":\"444\",\"name\":\"Carla\",\"mxid\":\"@telegram_444:x\"}";
                if (path.endsWith("nadie")) { status = 404; out = "{\"errcode\":\"M_NOT_FOUND\",\"error\":\"not found\"}"; }
            } else if (path.contains("/v3/create_dm/")) {
                out = "{\"id\":\"222\",\"dm_room_mxid\":\"!nuevo:x\"}";
            } else {
                status = 404;
                out = "{}";
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
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

    private BridgeContacts client(String loginId) {
        return new BridgeContacts("http://127.0.0.1:" + server.getAddress().getPort() + "/_matrix/provision", "tok", "@me:x", loginId);
    }

    @Test
    void listParsesContacts() throws Exception {
        List<Contact> c = client("L1").list().orElseThrow();
        assertEquals(2, c.size());
        assertEquals(new Contact("111", "Ana", List.of("tel:+573001112233"), "@telegram_111:x", "!dm:x"), c.get(0));
        assertNull(c.get(1).dmRoomId());
    }

    @Test
    void notSupportedIsEmptyNotAnError() throws Exception {
        supported = false;
        assertEquals(Optional.empty(), client("L1").list());
        assertEquals(Optional.empty(), client("L1").search("ana"));
    }

    @Test
    void searchSendsOnlyTheQuery() throws Exception {
        List<Contact> r = client("L1").search("ana").orElseThrow();
        assertEquals("Ana María", r.get(0).name());
        assertEquals("{\"query\":\"ana\"}", bodies.get(0));
        String uri = uris.get(0);
        assertTrue(uri.startsWith("POST /_matrix/provision/v3/search_users?user_id=%40me%3Ax&login_id=L1"), uri);
    }

    @Test
    void resolveFindsOrReturnsNull() throws Exception {
        assertEquals("Carla", client("L1").resolve("+573009998877").name());
        assertNull(client("L1").resolve("nadie"));
        assertTrue(uris.get(0).contains("/v3/resolve_identifier/%2B573009998877"), uris.get(0));
    }

    @Test
    void createDmReturnsTheRoom() throws Exception {
        assertEquals("!nuevo:x", client("L1").createDm("222"));
        assertTrue(uris.get(0).startsWith("POST /_matrix/provision/v3/create_dm/222"), uris.get(0));
    }

    @Test
    void everyCallCarriesTheChosenLogin() throws Exception {
        client("trabajo").list();
        client("personal").search("x");
        assertTrue(uris.get(0).contains("login_id=trabajo"));
        assertTrue(uris.get(1).contains("login_id=personal"));
        assertFalse(uris.get(1).contains("trabajo"));
    }

    @Test
    void pathSegmentsNeverTurnSpacesIntoPluses() throws Exception {
        client("L1").resolve("ana maria");
        assertTrue(uris.get(0).contains("/v3/resolve_identifier/ana%20maria"), uris.get(0));
    }
}

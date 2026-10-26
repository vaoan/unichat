package app.unichat.login;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BridgeLogin against a fake bridge that answers like mautrix bridgev2's provisioning API. */
class BridgeLoginTest {
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void fakeBridge() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(ex.getRequestMethod() + " " + path + "?" + ex.getRequestURI().getQuery() + " auth="
                    + ex.getRequestHeaders().getFirst("Authorization") + " " + body);
            int status = 200;
            String out;
            if (path.endsWith("/v3/login/flows")) {
                out = """
                        {"flows":[{"id":"qr","name":"QR","description":"Scan a QR code"},
                                  {"id":"phone","name":"Pairing code","description":"Link with a code"}]}""";
            } else if (path.endsWith("/v3/login/start/qr")) {
                out = """
                        {"login_id":"L1","type":"display_and_wait","step_id":"fi.mau.whatsapp.qr",
                         "instructions":"Scan the QR code","display_and_wait":{"type":"qr","data":"2@abc,def"}}""";
            } else if (path.endsWith("/v3/login/start/phone")) {
                out = """
                        {"login_id":"L2","type":"user_input","step_id":"fi.mau.whatsapp.phone","instructions":"Enter your number",
                         "user_input":{"fields":[{"type":"phone_number","id":"phone_number","name":"Phone number",
                         "description":"International format","pattern":"^\\\\+[0-9]+$"}]}}""";
            } else if (path.endsWith("/v3/login/step/L2/fi.mau.whatsapp.phone/user_input")) {
                out = """
                        {"login_id":"L2","type":"display_and_wait","step_id":"fi.mau.whatsapp.code",
                         "display_and_wait":{"type":"code","data":"ABCD-EFGH"}}""";
            } else if (path.endsWith("/display_and_wait")) {
                out = """
                        {"login_id":"L1","type":"complete","step_id":"fi.mau.whatsapp.complete",
                         "instructions":"Logged in as +57 300","complete":{"user_login_id":"573001112233"}}""";
            } else if (path.endsWith("/v3/login/start/broken")) {
                status = 403;
                out = "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"You don't have permission to log in\"}";
            } else if (path.endsWith("/v3/login/start/cookie")) {
                out = """
                        {"login_id":"L3","type":"cookies","step_id":"fi.mau.meta.cookies","cookies":{
                         "url":"https://www.instagram.com/","fields":[
                          {"id":"sessionid","required":true,"sources":[{"type":"cookie","name":"sessionid","cookie_domain":"instagram.com"}]},
                          {"id":"csrftoken","required":true,"sources":[{"type":"cookie","name":"csrftoken","cookie_domain":"instagram.com"}]}]}}""";
            } else {
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

    private BridgeLogin api() {
        return new BridgeLogin("http://127.0.0.1:" + server.getAddress().getPort() + "/_matrix/provision", "syt_tok", "@yo:localhost");
    }

    @Test
    void listsTheBridgesOwnFlows() throws Exception {
        List<BridgeLogin.Flow> flows = api().flows();
        assertEquals(List.of("qr", "phone"), flows.stream().map(BridgeLogin.Flow::id).toList());
        assertEquals("Scan a QR code", flows.get(0).description());
        assertTrue(requests.get(0).contains("user_id=@yo:localhost"), requests.get(0));
        assertTrue(requests.get(0).contains("auth=Bearer syt_tok"), "authenticated with the Matrix token");
    }

    @Test
    void qrFlowWaitsUntilComplete() throws Exception {
        BridgeLogin.Step s = api().start("qr");
        assertEquals("display_and_wait", s.type());
        assertEquals("qr", s.display().type());
        assertEquals("2@abc,def", s.display().data());
        BridgeLogin.Step done = api().await(s);
        assertTrue(done.isComplete());
        assertEquals("573001112233", done.userLoginId());
    }

    @Test
    void phoneFlowSubmitsTheFormThenShowsTheCode() throws Exception {
        BridgeLogin.Step s = api().start("phone");
        BridgeLogin.Field f = s.fields().get(0);
        assertEquals("phone_number", f.type());
        assertEquals("^\\+[0-9]+$", f.pattern());
        assertFalse(f.optional());
        BridgeLogin.Step code = api().submit(s, Map.of("phone_number", "+573001112233"));
        assertEquals("code", code.display().type());
        assertEquals("ABCD-EFGH", code.display().data());
        assertTrue(requests.get(requests.size() - 1).endsWith("{\"phone_number\":\"+573001112233\"}"));
    }

    @Test
    void cookieStepListsWhatTheBridgeNeeds() throws Exception {
        BridgeLogin.Step s = api().start("cookie");
        assertEquals("https://www.instagram.com/", s.cookies().url());
        assertEquals(List.of("sessionid", "csrftoken"), s.cookies().fields().stream().map(BridgeLogin.CookieField::id).toList());
        assertEquals("instagram.com", s.cookies().fields().get(0).sources().get(0).cookieDomain());
        assertNull(s.display());
    }

    @Test
    void bridgeErrorsKeepTheirMessage() {
        BridgeLogin.BridgeLoginException e = assertThrows(BridgeLogin.BridgeLoginException.class, () -> api().start("broken"));
        assertEquals("M_FORBIDDEN", e.errcode());
        assertEquals("You don't have permission to log in", e.getMessage());
    }
}

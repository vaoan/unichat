package app.unichat.matrix;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactServiceTest {
    @Test
    void theAccountCarriesItsBridgeLogin() {
        Account a = new Account(BridgeProtocol.accountId(Network.WHATSAPP, "573001112233"), Network.WHATSAPP, "Trabajo",
                AccountStatus.CONNECTED, null);
        assertEquals("573001112233", BridgeProtocol.loginIdOf(a));
        assertNull(BridgeProtocol.loginIdOf(new Account("whatsapp", Network.WHATSAPP, "x", AccountStatus.CONNECTED, null)));
    }

    @Test
    void withoutTheBridgeApiNothingIsOffered() throws Exception {
        MatrixChatService.setBridgeProvisioner(null);
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:9", "@me:x", "t");
        Account a = new Account("telegram:1", Network.TELEGRAM, "Telegram", AccountStatus.CONNECTED, null);
        assertEquals(new ChatService.ContactSupport(false, false), svc.contactSupport(a).get());
    }

    @Test
    void aNetworkWithoutSearchIsLearntFromTheFirstSearch() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes()));
            byte[] b = "{\"errcode\":\"M_UNRECOGNIZED\"}".getBytes();
            ex.sendResponseHeaders(404, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/_matrix/provision";
            MatrixChatService.setBridgeProvisioner(new BridgeProvisioner() {
                @Override public void ensure(Network n) { }
                @Override public String provisioningUrl(Network n) { return base; }
            });
            MatrixChatService svc = new MatrixChatService("http://127.0.0.1:9", "@me:x", "t");
            Account a = new Account("twitter:1", Network.X, "X", AccountStatus.CONNECTED, null);
            assertEquals(new ChatService.ContactSupport(false, true), svc.contactSupport(a).get(),
                    "search unknown until tried; nothing sent but the contact list request");
            assertTrue(bodies.stream().allMatch(String::isEmpty), "no made-up search was sent");
            java.util.concurrent.ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.ExecutionException.class, () -> svc.searchContacts(a, "ana").get());
            assertTrue(e.getCause() instanceof UnsupportedOperationException, e.toString());
            assertEquals(new ChatService.ContactSupport(false, false), svc.contactSupport(a).get());
        } finally {
            MatrixChatService.setBridgeProvisioner(null);
            server.stop(0);
        }
    }
}

package app.unichat.matrix;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Network;
import app.unichat.settings.Settings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression test for "a network only shows up after the sync finishes" (WhatsApp, Telegram… all of them).
 * A fake homeserver answers /sync only after {@link #SYNC_DELAY_MS}; connected networks and the cached chats
 * must be available long before that.
 */
class StartupBeforeSyncTest {
    private static final int SYNC_DELAY_MS = 5000;
    private static final String ME = "@yo:localhost";

    private HttpServer server;
    private final AtomicInteger syncCalls = new AtomicInteger();
    private volatile String sinceSeen;
    private MatrixChatService svc;

    @BeforeEach
    void fakeHomeserver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = "{}";
            int status = 200;
            if (path.endsWith("/sync")) {
                syncCalls.incrementAndGet();
                String q = ex.getRequestURI().getQuery();
                if (q != null && q.contains("since=")) sinceSeen = q.replaceAll(".*since=([^&]*).*", "$1");
                try {
                    Thread.sleep(SYNC_DELAY_MS); // a slow, busy server
                } catch (InterruptedException ignored) {
                    // test ending
                }
                body = "{\"next_batch\":\"s2\",\"rooms\":{}}";
            } else if (path.contains("/profile/")) {
                body = "{\"displayname\":\"x\"}";
            } else if (path.contains("/pushrules")) {
                body = "{\"global\":{}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        if (svc != null) svc.stop();
        server.stop(0);
        Settings s = Settings.get();
        for (Network n : Network.values()) s.set("matrix.logins." + n.protocolId(), null);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** What a previous run left on disk: the logins each bridge reported and the last full sync. */
    private void previousRun(MatrixChatService svc) throws Exception {
        long now = System.currentTimeMillis();
        Settings.get().set("matrix.logins.whatsapp", now + "\n573001112233\t+57 300 111 2233\tCONNECTED\n");
        Settings.get().set("matrix.logins.telegram", now + "\n900000042\tJuan\tCONNECTED\n");
        String sync = """
                {"next_batch":"s1","rooms":{"join":{
                  "!wa:localhost":{"state":{"events":[
                      {"type":"m.room.member","state_key":"@yo:localhost","sender":"@yo:localhost","content":{"membership":"join"}},
                      {"type":"m.room.member","state_key":"@whatsapp_573009998877:localhost","sender":"@whatsapp_573009998877:localhost",
                       "content":{"membership":"join","displayname":"Ana (WA)"}}]},
                    "timeline":{"events":[{"type":"m.room.message","event_id":"$1","sender":"@whatsapp_573009998877:localhost",
                       "origin_server_ts":1700000000000,"content":{"msgtype":"m.text","body":"hola"}}]}},
                  "!tg:localhost":{"state":{"events":[
                      {"type":"m.room.member","state_key":"@yo:localhost","sender":"@yo:localhost","content":{"membership":"join"}},
                      {"type":"m.room.member","state_key":"@telegram_42:localhost","sender":"@telegram_42:localhost",
                       "content":{"membership":"join","displayname":"Pedro"}}]},
                    "timeline":{"events":[{"type":"m.room.message","event_id":"$2","sender":"@telegram_42:localhost",
                       "origin_server_ts":1700000100000,"content":{"msgtype":"m.text","body":"buenas"}}]}}
                }}}""";
        Path f = svc.syncCacheFile();
        Files.createDirectories(f.getParent());
        Files.writeString(f, sync);
    }

    @Test
    void connectedNetworksAndCachedChatsShowBeforeTheSyncFinishes() throws Exception {
        svc = new MatrixChatService(url(), ME, "token");
        previousRun(svc);
        long t0 = System.currentTimeMillis();
        svc.start();

        await("cached chats", () -> svc.chats().size() == 2);
        await("accounts", () -> svc.accounts().stream().filter(a -> a.status() == AccountStatus.CONNECTED).count() == 2);
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed < 2000, "everything should come from disk, took " + elapsed + " ms");
        assertNotEquals(ConnectionState.ONLINE, svc.connectionState(), "the server hasn't answered yet");

        List<Account> accounts = svc.accounts();
        assertTrue(accounts.stream().anyMatch(a -> a.network() == Network.WHATSAPP && a.id().equals("whatsapp:573001112233")), accounts.toString());
        assertTrue(accounts.stream().anyMatch(a -> a.network() == Network.TELEGRAM && a.label().equals("Juan")), accounts.toString());

        Chat wa = svc.chat("!wa:localhost");
        assertEquals(Network.WHATSAPP, wa.network());
        assertEquals("Ana", wa.title(), "bridge suffix removed");
        assertEquals("hola", wa.lastPreview());
        assertEquals(Network.TELEGRAM, svc.chat("!tg:localhost").network());

        // The network sync resumes from the cached token instead of downloading everything again.
        await("catch-up sync", () -> "s1".equals(sinceSeen));
    }

    @Test
    void connectedNetworksShowEvenWithoutASyncCache() throws Exception {
        svc = new MatrixChatService(url(), ME, "token");
        previousRun(svc);
        Files.deleteIfExists(svc.syncCacheFile());
        svc.start();
        await("accounts", () -> svc.accounts().stream().map(Account::network).distinct().count() == 2);
        assertNotEquals(ConnectionState.ONLINE, svc.connectionState());
    }

    private static void await(String what, BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("Not ready within 2 s (sync takes " + SYNC_DELAY_MS + " ms): " + what);
    }
}

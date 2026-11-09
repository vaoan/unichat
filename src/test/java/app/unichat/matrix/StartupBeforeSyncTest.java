package app.unichat.matrix;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Network;
import app.unichat.service.Changes;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for "a network only shows up after the sync finishes" (WhatsApp, Telegram… all of them).
 * A fake homeserver holds every /sync until the test ends; connected networks and the cached chats must be
 * available without it.
 */
class StartupBeforeSyncTest {
    private static final String ME = "@yo:localhost";

    private HttpServer server;
    private final AtomicInteger syncCalls = new AtomicInteger();
    /** A slow, busy server: /sync answers only once the test is over. */
    private final CountDownLatch syncAnswers = new CountDownLatch(1);
    private final AtomicInteger syncsAnswered = new AtomicInteger();
    private final Changes changes = new Changes();
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
                changes.changed();
                try {
                    syncAnswers.await();
                } catch (InterruptedException ignored) {
                    // test ending
                }
                syncsAnswered.incrementAndGet();
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
        syncAnswers.countDown();
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
        start();

        changes.until(() -> svc.chats().size() == 2);
        changes.until(() -> svc.accounts().stream().filter(a -> a.status() == AccountStatus.CONNECTED).count() == 2);
        assertEquals(0, syncsAnswered.get(), "everything should come from disk");
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
        changes.until(() -> "s1".equals(sinceSeen));
    }

    @Test
    void connectedNetworksShowEvenWithoutASyncCache() throws Exception {
        svc = new MatrixChatService(url(), ME, "token");
        previousRun(svc);
        Files.deleteIfExists(svc.syncCacheFile());
        start();
        changes.until(() -> svc.accounts().stream().map(Account::network).distinct().count() == 2);
        assertNotEquals(ConnectionState.ONLINE, svc.connectionState());
    }

    @Test
    void networkWithoutCachedLoginStillShowsFromTheSyncCache() throws Exception {
        // Telegram after a logout: the bridge reported no logins, but its chats are in the cached sync.
        svc = new MatrixChatService(url(), ME, "token");
        previousRun(svc);
        Settings.get().set("matrix.logins.telegram", System.currentTimeMillis() + "\n");
        start();
        changes.until(() -> svc.chat("!tg:localhost") != null);
        changes.until(() -> svc.accounts().stream().anyMatch(a -> a.network() == Network.TELEGRAM));
        assertEquals(0, syncsAnswered.get(), "everything should come from disk");
        assertNotEquals(ConnectionState.ONLINE, svc.connectionState(), "the server hasn't answered yet");
    }

    private void start() {
        svc.addListener(changes.listener());
        svc.start();
    }
}

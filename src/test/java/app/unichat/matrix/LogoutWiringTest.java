package app.unichat.matrix;

import app.unichat.bridge.LoginsCache;
import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logging out through the bot chat stops the bridge once the bridge answers that no login is left; a login left, or a
 * refresh that wasn't a logout, never does.
 */
class LogoutWiringTest {
    private static final String ME = "@me:example.org";
    private static final String BOT = "@gmessagesbot:example.org";
    private static final String ROOM = "!gmessagesbot:example.org";

    private final SyncProcessor proc = new SyncProcessor(ME);
    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<Network> stopped = Collections.synchronizedList(new ArrayList<>());
    /** What the bridge's whoami lists. */
    private volatile String logins = "[]";
    private HttpServer server;
    private MatrixCore core;
    private BridgeAccounts accounts;

    @BeforeEach
    void fakeHomeserverAndBridge() throws Exception {
        synchronized (proc) {
            proc.process(MatrixClient.JSON.readTree(("{'rooms':{'join':{'" + ROOM + "':{'state':{'events':["
                    + member(ME) + "," + member(BOT) + "]},'timeline':{'events':[]}}}}}").replace('\'', '"')), false);
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String out;
            if (ex.getRequestURI().getPath().endsWith("/v3/whoami")) {
                out = "{\"logins\":" + logins + "}";
            } else {
                sent.add(MatrixClient.JSON.readTree(body).path("body").asText());
                out = "{\"event_id\":\"$e" + sent.size() + "\"}";
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        BridgeAccounts.provisioner = new BridgeProvisioner() {
            @Override public void ensure(Network n) { }
            @Override public String provisioningUrl(Network n) { return base + "/_matrix/provision"; }
            @Override public void loggedOut(Network n) { stopped.add(n); }
        };
        core = new MatrixCore(new MatrixClient(base, "tok"), proc, ME, "example.org", "tok");
        BotChat bots = new BotChat(core, new MessageSender(core, null));
        accounts = new BridgeAccounts(core, bots, new DiscordServers(core, bots, List::of));
    }

    @AfterEach
    void stop() {
        BridgeAccounts.provisioner = null;
        server.stop(0);
        Settings.get().set(LoginsCache.key(Network.GOOGLE_MESSAGES.protocolId()), null);
    }

    private static String member(String user) {
        return "{'type':'m.room.member','state_key':'" + user + "','sender':'" + user + "','event_id':'$m" + user.hashCode()
                + "','origin_server_ts':1,'content':{'membership':'join'}}";
    }

    private static Account account() {
        return new Account("gmessages:1", Network.GOOGLE_MESSAGES, "Pixel", AccountStatus.CONNECTED, null);
    }

    @Test
    void loggingOutOfTheLastAccountStopsTheBridge() throws Exception {
        accounts.disconnectAccount(account()).get();
        assertEquals(List.of("!gmessages logout 1"), sent);
        assertEquals(List.of(Network.GOOGLE_MESSAGES), stopped);
    }

    @Test
    void aLoginLeftKeepsItRunning() throws Exception {
        logins = "[{\"id\":\"2\",\"name\":\"Casa\"}]";
        accounts.disconnectAccount(account()).get();
        assertEquals(List.of(), stopped);
    }

    @Test
    void aRefreshThatFindsNoLoginNeverStopsIt() throws Exception {
        CountDownLatch refreshed = new CountDownLatch(1);
        core.listeners.add(new ChatService.Listener() {
            @Override public void accountsChanged() { refreshed.countDown(); }
        });
        accounts.loginFinished(Network.GOOGLE_MESSAGES); // a refresh, not a logout: whoami says none (mid-sign-in)
        refreshed.await();
        assertTrue(stopped.isEmpty(), stopped.toString());
    }
}

package app.unichat.server;

import app.unichat.bridge.BridgeStates;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** No internet is nobody's fault: a bridge started then gave up for good (Discord), and each restart made the next wait longer. */
class WatchdogInternetTest {
    private static final long NOW = 100 * 60_000;

    private HttpServer everyoneAnswers;
    private boolean online = true;
    private final List<String> restarted = new ArrayList<>();
    private Watchdog dog;

    @BeforeEach
    void setUp() throws Exception {
        everyoneAnswers = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        everyoneAnswers.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        everyoneAnswers.start();
        int port = everyoneAnswers.getAddress().getPort();
        dog = new Watchdog(Map.of("tuwunel", port, "discord", port, "telegram", port), () -> List.of("tuwunel", "discord", "telegram"),
                new BridgeStates(), (name, why) -> restarted.add(name + ": " + why), () -> online);
    }

    @AfterEach
    void tearDown() {
        everyoneAnswers.stop(0);
    }

    @Test
    void withoutInternetOnlyAHungProcessIsRestarted() {
        assertNull(Watchdog.reason(new Watchdog.Health(0, NOW - 60 * 60_000, 0), NOW, false), "disconnected for an hour");
        assertNull(Watchdog.reason(new Watchdog.Health(0, -1, 9), NOW, false), "drops");
        assertEquals("no responde", Watchdog.reason(new Watchdog.Health(3, -1, 0), NOW, false));
        assertEquals("5 desconexiones en 10 min", Watchdog.reason(new Watchdog.Health(0, -1, 5), NOW, true));
    }

    @Test
    void whenInternetReturnsEveryBridgeStartsAgainAtOnceButNotTheServer() {
        online = false;
        dog.check();
        assertEquals(List.of(), restarted);

        online = true;
        dog.check();
        assertEquals(List.of("discord: volvió internet", "telegram: volvió internet"), restarted);

        restarted.clear();
        dog.check();
        assertEquals(List.of(), restarted, "only once");
    }

    /** A keeper where facebook is fragile, and {@code safe} says whether it may be restarted. */
    private Watchdog withFragileFacebook(boolean[] safe) throws Exception {
        int port = everyoneAnswers.getAddress().getPort();
        BridgeStates states = new BridgeStates();
        for (int i = 0; i < Watchdog.FLAPS; i++) {
            states.record("facebook", new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    "{\"state_event\":\"" + "TRANSIENT_DISCONNECT" + "\",\"remote_id\":\"1\"}"), BridgeStates.now());
            states.record("facebook", new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    "{\"state_event\":\"CONNECTED\",\"remote_id\":\"1\"}"), BridgeStates.now());
        }
        return new Watchdog(Map.of("facebook", port, "telegram", port), () -> List.of("facebook", "telegram"), states,
                (name, why) -> restarted.add(name + ": " + why), () -> online, new Watchdog.SessionKeeper() {
                    @Override public boolean fragile(String bridge) { return bridge.equals("facebook"); }
                    @Override public boolean safeToRestart(String bridge) { return !bridge.equals("facebook") || safe[0]; }
                    @Override public void tick(boolean on, long now) { }
                });
    }

    @Test
    void returningInternetDoesNotRestartWhatCouldLoseItsSession() throws Exception {
        boolean[] safe = {false};
        Watchdog d = withFragileFacebook(safe);
        online = false;
        d.check();
        online = true;
        d.check();
        assertEquals(List.of("telegram: volvió internet"), restarted, "and flapping doesn't either while it's not safe");
    }

    @Test
    void aFragileBridgeIsRestartedForItsOwnTroublesOnceItsSafe() throws Exception {
        boolean[] safe = {true};
        Watchdog d = withFragileFacebook(safe);
        d.check();
        assertEquals(List.of("facebook: 5 desconexiones en 10 min"), restarted);
    }

    @Test
    void returningInternetDoesNotWaitForTheCooldown() {
        online = false;
        dog.check();
        online = true;
        dog.check();
        online = false;
        dog.check();
        restarted.clear();
        online = true;
        dog.check();
        assertEquals(List.of("discord: volvió internet", "telegram: volvió internet"), restarted);
    }
}

package app.unichat.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeStatesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BridgeStates s = new BridgeStates();

    private void report(String bridge, String json, long at) throws Exception {
        s.record(bridge, JSON.readTree(json), at);
    }

    @Test
    void eachAccountKeepsItsLatestStateAndWhenItBegan() throws Exception {
        report("whatsapp", "{\"state_event\":\"CONNECTED\",\"remote_id\":\"573001\",\"remote_name\":\"+57 300\"}", 1_000);
        report("whatsapp", "{\"state_event\":\"TRANSIENT_DISCONNECT\",\"remote_id\":\"573001\"}", 2_000);
        report("whatsapp", "{\"state_event\":\"TRANSIENT_DISCONNECT\",\"remote_id\":\"573001\"}", 9_000);
        report("whatsapp", "{\"state_event\":\"RUNNING\"}", 9_500);
        List<BridgeStates.State> acc = s.accounts("whatsapp");
        assertEquals(1, acc.size(), "the bridge's own state isn't an account");
        assertEquals("TRANSIENT_DISCONNECT", acc.get(0).event());
        assertEquals(2_000, acc.get(0).since(), "a repeated state keeps its start");
        assertEquals("+57 300", acc.get(0).remoteName(), "the name survives reports without it");
        assertEquals(2_000, s.troubledSince("whatsapp"));
        assertEquals(1, s.dropsWithin("whatsapp", 600_000, 9_000), "one drop, reported twice");
    }

    @Test
    void aClosedSessionIsNotSomethingARestartFixes() throws Exception {
        report("whatsapp", "{\"state_event\":\"LOGGED_OUT\",\"remote_id\":\"1\"}", 1_000);
        assertEquals(-1, s.troubledSince("whatsapp"));
    }

    @Test
    void dropsAreCountedInAWindow() throws Exception {
        for (int i = 0; i < 6; i++) {
            report("discord", "{\"state_event\":\"TRANSIENT_DISCONNECT\",\"remote_id\":\"9\"}", i * 60_000L);
            report("discord", "{\"state_event\":\"CONNECTED\",\"remote_id\":\"9\"}", i * 60_000L + 5_000);
        }
        assertEquals(6, s.dropsWithin("discord", 600_000, 360_000));
        assertEquals(1, s.dropsWithin("discord", 600_000, 900_000), "old ones fall out of the window");
        assertEquals(-1, s.troubledSince("discord"), "connected again");
    }

    @Test
    void aRestartShowsTheAccountsAsConnectingAndForgetsTheDrops() throws Exception {
        report("discord", "{\"state_event\":\"TRANSIENT_DISCONNECT\",\"remote_id\":\"9\",\"remote_name\":\"yo\"}", 1_000);
        int[] heard = {0};
        s.addListener(() -> heard[0]++);
        s.restarting("discord", 50_000);
        BridgeStates.State st = s.accounts("discord").get(0);
        assertEquals("CONNECTING", st.event());
        assertEquals(50_000, st.since());
        assertEquals("yo", st.remoteName());
        assertEquals(0, s.dropsWithin("discord", 600_000, 50_000));
        assertTrue(heard[0] > 0);
    }

    @Test
    void thePingAnswerCarriesEveryAccount() throws Exception {
        report("telegram", "{\"state_event\":\"RUNNING\",\"remoteState\":{\"a\":{\"state_event\":\"CONNECTED\",\"remote_id\":\"a\"},"
                + "\"b\":{\"state_event\":\"BAD_CREDENTIALS\",\"remote_id\":\"b\"}}}", 1_000);
        assertEquals(2, s.accounts("telegram").size());
    }
}

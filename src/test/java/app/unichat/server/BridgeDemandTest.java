package app.unichat.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bridge with no account cost battery for nothing (Google Messages ran with "No user logins found"): it doesn't run
 * until the person opens its sign-in, and one with an account always runs.
 */
class BridgeDemandTest {
    private final List<String> calls = new ArrayList<>();
    /** How many probes go unanswered before the bridge answers. */
    private int unanswered;

    private BridgeDemand demand(Set<String> withAccount, int tries) {
        return new BridgeDemand(withAccount::contains, b -> {
            calls.add("probe " + b);
            return unanswered-- <= 0;
        }, Duration.ZERO, tries);
    }

    @Test
    void aBridgeWithoutALoginIsNotStartedAtLaunch() {
        BridgeDemand d = demand(Set.of("whatsapp"), 1);
        assertEquals(List.of("whatsapp"), d.atLaunch(List.of("whatsapp", "gmessages")));
        assertFalse(d.isMeant("gmessages"));
    }

    @Test
    void aBridgeWithALoginStartsAtLaunch() {
        BridgeDemand d = demand(Set.of("whatsapp", "discord"), 1);
        assertEquals(List.of("whatsapp", "discord"), d.atLaunch(List.of("whatsapp", "discord")));
        assertTrue(d.isMeant("discord"));
    }

    @Test
    void openingTheSignInStartsTheBridge() throws Exception {
        BridgeDemand d = demand(Set.of(), 5);
        d.atLaunch(List.of("gmessages"));
        unanswered = 2;
        d.open("gmessages", false, b -> calls.add("start " + b));
        assertEquals(List.of("start gmessages", "probe gmessages", "probe gmessages", "probe gmessages"), calls,
                "started first, and the sign-in waits until it answers");
        assertTrue(d.isMeant("gmessages"));
    }

    @Test
    void aBridgeAlreadyRunningIsOnlyWaitedFor() throws Exception {
        BridgeDemand d = demand(Set.of("whatsapp"), 1);
        d.open("whatsapp", true, b -> calls.add("start " + b));
        assertEquals(List.of("probe whatsapp"), calls);
    }

    @Test
    void aSignInWhoseBridgeNeverAnswersSaysSo() {
        BridgeDemand d = demand(Set.of(), 3);
        unanswered = Integer.MAX_VALUE;
        assertThrows(IOException.class, () -> d.open("gmessages", false, b -> calls.add("start " + b)));
        assertEquals(List.of("start gmessages", "probe gmessages", "probe gmessages", "probe gmessages"), calls);
    }

    @Test
    void aLoginMadeThisSessionKeepsItsBridgeRunning() throws Exception {
        BridgeDemand d = demand(Set.of("whatsapp"), 1);
        d.atLaunch(List.of("whatsapp", "gmessages"));
        d.open("gmessages", false, b -> { });
        assertEquals(List.of("whatsapp", "gmessages"), d.meant(List.of("whatsapp", "gmessages", "signal")),
                "the watchdog watches it from now on");
        assertEquals(List.of("whatsapp", "gmessages"), d.atLaunch(List.of("whatsapp", "gmessages")),
                "and a server started again in this session starts it too");
    }
}

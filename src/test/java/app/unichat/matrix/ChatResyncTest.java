package app.unichat.matrix;

import app.unichat.bridge.BridgePorts;
import app.unichat.model.Network;
import app.unichat.server.BridgeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Telegram only lists your chats once, at login: when Unichat raises the limit, ask for one more pass. */
class ChatResyncTest {
    @Test
    void resyncOnceForEachNewLimit() {
        int limit = BridgePorts.TELEGRAM_CREATE_LIMIT;
        assertTrue(BridgeProtocol.needsChatResync(null, limit), "never done");
        assertTrue(BridgeProtocol.needsChatResync("15", limit), "limit went up");
        assertFalse(BridgeProtocol.needsChatResync(Integer.toString(limit), limit), "already done for this limit");
        assertTrue(BridgeProtocol.needsChatResync("garbage", limit));
    }

    @Test
    void theBridgeCommandIsSyncChats() {
        assertEquals("!telegram sync-chats", BridgeProtocol.botCommand(Network.TELEGRAM, "sync-chats"));
        assertEquals(100, BridgePorts.TELEGRAM_CREATE_LIMIT);
    }

    @Test
    void aBrokenBotRoomIsRecognised() {
        assertTrue(BridgeProtocol.brokenRoom(new MatrixException(500, "M_UNKNOWN",
                "cannot create a non-create event in a room with no forward extremities in !x:localhost")));
        assertFalse(BridgeProtocol.brokenRoom(new MatrixException(403, "M_FORBIDDEN", "not in room")));
        assertFalse(BridgeProtocol.brokenRoom(new java.io.IOException("timeout")));
    }
}

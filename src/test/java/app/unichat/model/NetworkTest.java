package app.unichat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NetworkTest {
    @Test
    void detectsNetworkFromPuppetsBotsAndProtocolIds() {
        assertEquals(Network.WHATSAPP, Network.fromUserId("@whatsapp_573001112233:example.org"));
        assertEquals(Network.TELEGRAM, Network.fromUserId("@telegrambot:example.org"));
        assertNull(Network.fromUserId("@alice:example.org"));
        assertEquals(Network.MESSENGER, Network.fromProtocolId("facebook"));
        assertEquals(Network.MESSENGER, Network.fromProtocolId("meta"));
        assertEquals(Network.GOOGLE_MESSAGES, Network.fromProtocolId("gmessages"));
        assertNull(Network.fromProtocolId("unknown"));
    }
}

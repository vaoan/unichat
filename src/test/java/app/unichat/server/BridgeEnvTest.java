package app.unichat.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Go bridges collect garbage sooner (GOGC, GOMEMLIMIT); the homeserver (Rust) is never given them. */
class BridgeEnvTest {
    @Test
    void aGoBridgeCollectsSoonerWithASoftLimit() {
        assertEquals(java.util.Map.of("GOGC", "50", "GOMEMLIMIT", "64MiB"), BridgeEnv.forService("telegram"));
        assertEquals("128MiB", BridgeEnv.forService("discord").get("GOMEMLIMIT"));
    }

    @Test
    void everyBridgeIsGivenIt() {
        for (String b : new String[] {"whatsapp", "telegram", "signal", "instagram", "facebook", "discord", "slack", "gmessages", "twitter", "linkedin"}) {
            assertTrue(BridgeEnv.forService(b).containsKey("GOGC"), b);
        }
    }

    @Test
    void theHomeserverGetsNone() {
        assertTrue(BridgeEnv.forService("tuwunel").isEmpty());
    }
}

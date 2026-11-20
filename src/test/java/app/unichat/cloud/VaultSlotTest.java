package app.unichat.cloud;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class VaultSlotTest {
    private final byte[] vk = VaultCrypto.randomKey();

    @Test
    void eachSlotOpensTheVaultKeyWithItsOwnSecretOnly() throws Exception {
        RecoveryCode code = RecoveryCode.random();
        VaultSlot recovery = VaultSlot.wrap(VaultSlot.Method.RECOVERY, "printed", 1, VaultSlot.recoveryKey(code), JsonNodes.empty(), vk);
        assertArrayEquals(vk, recovery.unwrap(VaultSlot.recoveryKey(code)));
        assertThrows(VaultCrypto.Broken.class, () -> recovery.unwrap(VaultSlot.recoveryKey(RecoveryCode.random())));

        ObjectNode params = VaultSlot.newPasswordParams();
        char[] pw = "correct horse battery".toCharArray();
        VaultSlot password = VaultSlot.wrap(VaultSlot.Method.PASSWORD, "password", 1, VaultSlot.passwordKey(pw, params), params, vk);
        assertArrayEquals(vk, password.unwrap(VaultSlot.passwordKey(pw, password.params())));
        assertThrows(VaultCrypto.Broken.class, () -> password.unwrap(VaultSlot.passwordKey("another horse battery".toCharArray(), password.params())));

        byte[] computerKey = VaultCrypto.randomKey();
        VaultSlot computer = VaultSlot.wrap(VaultSlot.Method.COMPUTER, "this Mac", 2, computerKey, JsonNodes.empty(), vk);
        assertArrayEquals(vk, computer.unwrap(computerKey));
        assertThrows(VaultCrypto.Broken.class, () -> computer.unwrap(VaultCrypto.randomKey()));
    }

    @Test
    void wrongSecretsOpenNothing() {
        VaultSlot slot = VaultSlot.wrap(VaultSlot.Method.COMPUTER, "x", 1, VaultCrypto.randomKey(), JsonNodes.empty(), vk);
        assertThrows(VaultCrypto.Broken.class, () -> slot.unwrap(vk));
        assertThrows(VaultCrypto.Broken.class, () -> slot.unwrap(new byte[5]));
        assertThrows(VaultCrypto.Broken.class, () -> slot.unwrap(new byte[32]));
    }

    @Test
    void passwordParamsCarryAFreshSalt() {
        ObjectNode a = VaultSlot.newPasswordParams(), b = VaultSlot.newPasswordParams();
        assertEquals("argon2id", a.get("kdf").asText());
        assertEquals(65536, a.get("m").asInt());
        assertEquals(3, a.get("t").asInt());
        assertEquals(1, a.get("p").asInt());
        assertEquals(16, Base64.getDecoder().decode(a.get("salt").asText()).length);
        assertNotEquals(a.get("salt"), b.get("salt"));
    }

    @Test
    void aSlotSurvivesItsJson() throws Exception {
        byte[] key = VaultCrypto.randomKey();
        VaultSlot slot = VaultSlot.wrap(VaultSlot.Method.PASSWORD, "pw", 7, key, VaultSlot.newPasswordParams(), vk);
        VaultSlot back = VaultSlot.parse(slot.toJson());
        assertEquals(slot.id(), back.id());
        assertEquals(VaultSlot.Method.PASSWORD, back.method());
        assertEquals("pw", back.label());
        assertEquals(slot.created(), back.created());
        assertEquals(7, back.generation());
        assertEquals(slot.params(), back.params());
        assertArrayEquals(slot.wrapped(), back.wrapped());
        assertArrayEquals(vk, back.unwrap(key));

        assertNull(VaultSlot.parse("garbage".getBytes(StandardCharsets.UTF_8)));
        assertNull(VaultSlot.parse("[]".getBytes(StandardCharsets.UTF_8)));
        assertNull(VaultSlot.parse("{}".getBytes(StandardCharsets.UTF_8)));
        assertNull(VaultSlot.parse(new String(slot.toJson(), StandardCharsets.UTF_8).replace("PASSWORD", "MAGIC").getBytes(StandardCharsets.UTF_8)));
        assertNull(VaultSlot.parse(new String(slot.toJson(), StandardCharsets.UTF_8).replaceAll("\"wrapped\":\"[^\"]*\"", "\"wrapped\":\"***\"").getBytes(StandardCharsets.UTF_8)));
        assertNull(VaultSlot.parse(new String(slot.toJson(), StandardCharsets.UTF_8).replaceAll("\"created\":\"[^\"]*\"", "\"created\":\"yesterday\"").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aSlotMovedToAnotherIdDoesntOpen() throws Exception {
        byte[] key = VaultCrypto.randomKey();
        VaultSlot slot = VaultSlot.wrap(VaultSlot.Method.COMPUTER, "x", 1, key, JsonNodes.empty(), vk);
        VaultSlot moved = new VaultSlot("another-id", slot.method(), slot.label(), Instant.now(), 1, slot.params(), slot.wrapped());
        assertThrows(VaultCrypto.Broken.class, () -> moved.unwrap(key));
        assertArrayEquals(vk, slot.unwrap(key));
    }

    @Test
    void aPasswordSlotWithABadSaltIsNoSlotAndGivesNoKey() throws Exception {
        VaultSlot good = VaultSlot.wrap(VaultSlot.Method.PASSWORD, "pw", 1, VaultCrypto.randomKey(), VaultSlot.newPasswordParams(), vk);
        String json = new String(good.toJson(), StandardCharsets.UTF_8);
        String salt = good.params().get("salt").asText();
        String eight = Base64.getEncoder().encodeToString(new byte[8]);
        for (String bad : new String[] {"\"salt\":\"" + salt + "\",", "\"salt\":\"%%%\"", "\"salt\":\"" + eight + "\"", "\"salt\":7"}) {
            String changed = json.replace("\"salt\":\"" + salt + "\"", bad.startsWith("\"salt\":\"" + salt + "\",") ? "\"x\":1" : bad);
            assertNull(VaultSlot.parse(changed.getBytes(StandardCharsets.UTF_8)), changed);
        }
        char[] pw = "twelve chars!".toCharArray();
        assertThrows(VaultCrypto.Broken.class, () -> VaultSlot.passwordKey(pw, empty()));
        ObjectNode notBase64 = empty().put("salt", "%%%");
        assertThrows(VaultCrypto.Broken.class, () -> VaultSlot.passwordKey(pw, notBase64));
        ObjectNode short8 = empty().put("salt", eight);
        assertThrows(VaultCrypto.Broken.class, () -> VaultSlot.passwordKey(pw, short8));
    }

    @Test
    void generationLabelAndParamsMustBeSane() {
        VaultSlot slot = VaultSlot.wrap(VaultSlot.Method.COMPUTER, "x", 1, VaultCrypto.randomKey(), empty(), vk);
        String json = new String(slot.toJson(), StandardCharsets.UTF_8);
        for (String bad : new String[] {"\"generation\":1", "\"generation\":-1", "\"generation\":1.5", "\"generation\":1e30", "\"generation\":99999999999999999999"}) {
            String changed = json.replace("\"generation\":1", bad.equals("\"generation\":1") ? "\"generation\":1" : bad);
            if (bad.equals("\"generation\":1")) assertEquals(1, VaultSlot.parse(changed.getBytes(StandardCharsets.UTF_8)).generation());
            else assertNull(VaultSlot.parse(changed.getBytes(StandardCharsets.UTF_8)), bad);
        }
        assertNull(VaultSlot.parse(json.replace("\"label\":\"x\"", "\"label\":null").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> VaultSlot.wrap(VaultSlot.Method.COMPUTER, null, 1, VaultCrypto.randomKey(), empty(), vk));
        assertThrows(IllegalArgumentException.class, () -> VaultSlot.wrap(VaultSlot.Method.COMPUTER, "x", 1, VaultCrypto.randomKey(), null, vk));
        assertThrows(IllegalArgumentException.class, () -> VaultSlot.wrap(VaultSlot.Method.COMPUTER, "x", -1, VaultCrypto.randomKey(), empty(), vk));
    }

    private static ObjectNode empty() {
        return JsonNodes.empty();
    }

    /** Empty params, as a computer's or the recovery slot's. */
    private static final class JsonNodes {
        static ObjectNode empty() {
            return new ObjectMapper().createObjectNode();
        }
    }
}

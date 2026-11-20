package app.unichat.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * One way into the vault: the vault key sealed by that way's own key. The slot's id is the sealing path, so the same
 * bytes under another id open nothing. A slot is a small JSON file in the account; the recovery code, the password or
 * the computer's key that opens it is never in it.
 */
record VaultSlot(String id, Method method, String label, Instant created, long generation, ObjectNode params, byte[] wrapped) {
    enum Method { RECOVERY, PASSWORD, COMPUTER, GOOGLE, PASSKEY }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT = 16;

    static VaultSlot wrap(Method m, String label, long generation, byte[] slotKey, ObjectNode params, byte[] vk) {
        if (label == null || params == null || generation < 0) throw new IllegalArgumentException("A slot needs a label, params and a generation");
        String id = UUID.randomUUID().toString();
        return new VaultSlot(id, m, label, Instant.now(), generation, params, VaultCrypto.seal(slotKey, path(id), vk));
    }

    byte[] unwrap(byte[] slotKey) throws VaultCrypto.Broken {
        return VaultCrypto.open(slotKey, path(id), wrapped);
    }

    static byte[] recoveryKey(RecoveryCode code) {
        return VaultCrypto.hkdf(code.bits(), "unichat-vault recovery");
    }

    /** Params come from the account: a salt that isn't 16 bytes of base64 is as broken as a wrong password. */
    static byte[] passwordKey(char[] password, ObjectNode params) throws VaultCrypto.Broken {
        byte[] salt = salt(params);
        if (salt == null) throw new VaultCrypto.Broken();
        return VaultCrypto.argon2id(password, salt);
    }

    private static byte[] salt(ObjectNode params) {
        if (!params.path("salt").isTextual()) return null;
        try {
            byte[] salt = Base64.getDecoder().decode(params.get("salt").asText());
            return salt.length == SALT ? salt : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The parameters as written in the slot; Argon2id itself runs with {@code VaultCrypto}'s constants. */
    static ObjectNode newPasswordParams() {
        byte[] salt = new byte[SALT];
        RANDOM.nextBytes(salt);
        ObjectNode p = JSON.createObjectNode();
        p.put("salt", Base64.getEncoder().encodeToString(salt));
        p.put("kdf", "argon2id").put("m", 64 * 1024).put("t", 3).put("p", 1);
        return p;
    }

    byte[] toJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", id).put("method", method.name()).put("label", label).put("created", created.toString());
        n.put("generation", generation);
        n.set("params", params);
        n.put("wrapped", Base64.getEncoder().encodeToString(wrapped));
        try {
            return JSON.writeValueAsBytes(n);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The slot in these bytes, or null for anything that isn't one. */
    static VaultSlot parse(byte[] json) {
        try {
            JsonNode n = JSON.readTree(json);
            if (n == null || !n.isObject() || !n.path("id").isTextual() || !n.path("label").isTextual()
                || !n.path("generation").isIntegralNumber() || !n.get("generation").canConvertToLong() || n.get("generation").asLong() < 0
                || !(n.get("params") instanceof ObjectNode params)) return null;
            Method method = Method.valueOf(text(n, "method"));
            if (method == Method.PASSWORD && salt(params) == null) return null;
            return new VaultSlot(n.get("id").asText(), method, n.get("label").asText(),
                Instant.parse(text(n, "created")), n.get("generation").asLong(), params,
                Base64.getDecoder().decode(text(n, "wrapped")));
        } catch (IOException | IllegalArgumentException | DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        if (!n.path(field).isTextual()) throw new IllegalArgumentException(field);
        return n.get(field).asText();
    }

    private static String path(String id) {
        return "slot:" + id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VaultSlot s && id.equals(s.id) && method == s.method && label.equals(s.label)
            && created.equals(s.created) && generation == s.generation && params.equals(s.params) && Arrays.equals(wrapped, s.wrapped);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "VaultSlot[" + id + ", " + method + "]";
    }
}

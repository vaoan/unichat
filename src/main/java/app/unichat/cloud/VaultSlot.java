package app.unichat.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * One way into the vault: the vault key sealed by that way's own key. The vault's id, the method, the slot's id and its
 * generation are the sealing path, so the same bytes in another vault, as another method, under another id or claiming
 * another generation open nothing. A slot is
 * a small JSON file in the account; the recovery code, the password or the computer's key that opens it is never in it.
 */
@SuppressWarnings("ArrayRecordComponent") // equals and hashCode below compare the bytes
record VaultSlot(String id, String vault, Method method, String label, Instant created, long generation, ObjectNode params, byte[] wrapped) {
    enum Method { RECOVERY, PASSWORD, COMPUTER, GOOGLE, PASSKEY }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT = 16;

    static VaultSlot wrap(String vault, Method m, String label, long generation, byte[] slotKey, ObjectNode params, byte[] vk) {
        return wrap(vault, UUID.randomUUID().toString(), m, label, generation, slotKey, params, vk);
    }

    /** The same under a given id: a computer's slot is its machine's id, so it has one and only one. */
    static VaultSlot wrap(String vault, String id, Method m, String label, long generation, byte[] slotKey, ObjectNode params, byte[] vk) {
        if (vault == null || id == null || m == null || label == null || params == null || generation < 0) {
            throw new IllegalArgumentException("A slot needs a vault, an id, a method, a label, params and a generation");
        }
        return new VaultSlot(id, vault, m, label, Instant.now(), generation, params, VaultCrypto.seal(slotKey, path(vault, m, id, generation), vk));
    }

    byte[] unwrap(byte[] slotKey) throws VaultCrypto.Broken {
        return VaultCrypto.open(slotKey, path(vault, method, id, generation), wrapped);
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

    private static byte @Nullable [] salt(ObjectNode params) {
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
        n.put("id", id).put("vault", vault).put("method", method.name()).put("label", label).put("created", created.toString());
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
    static @Nullable VaultSlot parse(byte[] json) {
        try {
            JsonNode n = JSON.readTree(json);
            if (n == null || !n.isObject() || !n.path("id").isTextual() || !n.path("vault").isTextual() || !n.path("label").isTextual()
                || !n.path("generation").isIntegralNumber() || !n.get("generation").canConvertToLong() || n.get("generation").asLong() < 0
                || !(n.get("params") instanceof ObjectNode params)) return null;
            Method method = Method.valueOf(text(n, "method"));
            if (method == Method.PASSWORD && salt(params) == null) return null;
            return new VaultSlot(n.get("id").asText(), n.get("vault").asText(), method, n.get("label").asText(),
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

    /** What a slot is sealed under: its vault, method, id and generation, so a copy put back claiming a later one opens nothing. */
    private static String path(String vault, Method m, String id, long generation) {
        return "slot:" + vault + ":" + m + ":" + id + ":" + generation;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VaultSlot s && id.equals(s.id) && vault.equals(s.vault) && method == s.method && label.equals(s.label)
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

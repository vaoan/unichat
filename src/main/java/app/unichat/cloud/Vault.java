package app.unichat.cloud;

import app.unichat.support.I18n;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * The vault in the person's account: {@link #INFO} says there is one, and each way in is a slot of its own under
 * {@link #SLOTS}, so adding one never rewrites another and any of them opens the same vault key. Everything read from
 * the account is untrusted: what isn't a vault or a slot reads as none.
 */
final class Vault {
    static final String ROOT = CloudAccount.ROOT + "/Bóveda", SLOTS = ROOT + "/llaves", INFO = ROOT + "/boveda.json";
    static final int MIN_PASSWORD = 12;
    private static final String JSON_FILE = ".json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private Vault() {}

    record Info(String id, Instant created, long generation) {}

    /** A new vault: its key, the code to print (shown once) and this computer's way in. */
    record Created(byte[] vk, RecoveryCode code, Computer computer, Info info) {
        @Override
        public String toString() {
            return "Vault.Created[" + info.id() + "]";
        }
    }

    /** A computer's way in: the key its slot is wrapped with, and its X25519 pair (the public half is in the slot). */
    record Computer(byte[] key, KeyPair pair) {
        @Override
        public String toString() {
            return "Vault.Computer[…]";
        }
    }

    /** There already is a vault in the account (perhaps another computer made it a moment ago): open it instead. */
    static final class Exists extends Exception {
        Exists() {
            super("There already is a vault in the account");
        }
    }

    /** The vault in the account, or null when there's none (or what's there isn't one). */
    static Info info(Storage s) throws IOException {
        byte[] json = s.get(INFO);
        if (json == null) return null;
        try {
            JsonNode n = JSON.readTree(json);
            if (n == null || !n.path("id").isTextual() || n.get("id").asText().isBlank() || !n.path("created").isTextual()
                || !n.path("generation").isIntegralNumber() || !n.get("generation").canConvertToLong() || n.get("generation").asLong() < 0) {
                return null;
            }
            return new Info(n.get("id").asText(), Instant.parse(n.get("created").asText()), n.get("generation").asLong());
        } catch (IOException | DateTimeParseException e) {
            return null;
        }
    }

    static long generation(Storage s) throws IOException {
        Info info = info(s);
        if (info == null) throw new IOException(I18n.t("cloud.error.noVault"));
        return info.generation();
    }

    /**
     * Makes the vault with its recovery code and this computer's slot. Two computers may do it at once: whoever reads back
     * another's {@link #INFO} after writing its own stops before writing any slot, leaving the other's vault as it is.
     */
    static Created create(Storage s, String machine, String machineName) throws IOException, Exists {
        if (s.exists(INFO)) throw new Exists();
        Info mine = new Info(UUID.randomUUID().toString(), Instant.now(), 0);
        s.mkdirs(ROOT);
        s.put(INFO, json(mine));
        Info read = info(s);
        if (read == null || !read.id().equals(mine.id())) throw new Exists();
        s.mkdirs(SLOTS);
        byte[] vk = VaultCrypto.randomKey();
        RecoveryCode code = writeRecovery(s, vk, mine.generation());
        Computer computer = writeComputer(s, vk, machine, machineName, mine.generation());
        return new Created(vk, code, computer, mine);
    }

    /** Every slot in the account; a file that isn't one, or whose name isn't its id, is skipped. */
    static List<VaultSlot> slots(Storage s) throws IOException {
        List<VaultSlot> out = new ArrayList<>();
        for (String name : s.list(SLOTS)) {
            if (!name.endsWith(JSON_FILE)) continue;
            String id = name.substring(0, name.length() - JSON_FILE.length());
            if (!ours(id)) continue;
            byte[] json = s.get(path(id));
            VaultSlot slot = json == null ? null : VaultSlot.parse(json);
            if (slot != null && slot.id().equals(id)) out.add(slot);
        }
        return out;
    }

    /** The vault key from one slot and its way in's key. */
    static byte[] open(Storage s, VaultSlot slot, byte[] slotKey) throws VaultCrypto.Broken {
        return slot.unwrap(slotKey);
    }

    /** This computer's way in, as {@code machine}'s own slot (one per computer: a second replaces its first). */
    static Computer addComputer(Storage s, byte[] vk, String machine, String name) throws IOException {
        return writeComputer(s, vk, machine, name, generation(s));
    }

    static VaultSlot addPassword(Storage s, byte[] vk, char[] password) throws IOException {
        if (password.length < MIN_PASSWORD) throw new IllegalArgumentException("A password needs at least " + MIN_PASSWORD + " characters");
        ObjectNode params = VaultSlot.newPasswordParams();
        byte[] key;
        try {
            key = VaultSlot.passwordKey(password, params);
        } catch (VaultCrypto.Broken e) {
            throw new IllegalStateException("Fresh password params were refused", e);
        }
        return write(s, VaultSlot.wrap(VaultSlot.Method.PASSWORD, "password", generation(s), key, params, vk), key);
    }

    /** A new recovery code; the old one's slot goes once the new one is there, so there's always one. */
    static RecoveryCode newRecovery(Storage s, byte[] vk) throws IOException {
        List<VaultSlot> old = slots(s).stream().filter(slot -> slot.method() == VaultSlot.Method.RECOVERY).toList();
        RecoveryCode code = writeRecovery(s, vk, generation(s));
        for (VaultSlot slot : old) removeSlot(s, slot.id());
        return code;
    }

    /** Ids are ours (UUIDs): one from the account that isn't never becomes a path to delete. */
    static void removeSlot(Storage s, String id) throws IOException {
        if (!ours(id)) throw new IllegalArgumentException("Not a slot id");
        s.delete(path(id));
    }

    private static RecoveryCode writeRecovery(Storage s, byte[] vk, long generation) throws IOException {
        RecoveryCode code = RecoveryCode.random();
        byte[] key = VaultSlot.recoveryKey(code);
        write(s, VaultSlot.wrap(VaultSlot.Method.RECOVERY, "recovery", generation, key, JSON.createObjectNode(), vk), key);
        return code;
    }

    private static Computer writeComputer(Storage s, byte[] vk, String machine, String name, long generation) throws IOException {
        if (!ours(machine)) throw new IllegalArgumentException("Not a machine id");
        KeyPair pair = x25519();
        ObjectNode params = JSON.createObjectNode();
        params.put("x25519", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        byte[] key = VaultCrypto.randomKey();
        s.put(path(machine), VaultSlot.wrap(machine, VaultSlot.Method.COMPUTER, name, generation, key, params, vk).toJson());
        return new Computer(key, pair);
    }

    /** Writes the slot and forgets its key, which only the way in can make again. */
    private static VaultSlot write(Storage s, VaultSlot slot, byte[] key) throws IOException {
        try {
            s.put(path(slot.id()), slot.toJson());
            return slot;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static KeyPair x25519() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean ours(String id) {
        return id != null && TextSync.MACHINE.matcher(id).matches();
    }

    private static String path(String id) {
        return SLOTS + "/" + id + JSON_FILE;
    }

    private static byte[] json(Info info) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", info.id()).put("created", info.created().toString()).put("generation", info.generation());
        try {
            return JSON.writeValueAsBytes(n);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

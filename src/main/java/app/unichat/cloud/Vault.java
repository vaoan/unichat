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
            if (n == null || !n.path("id").isTextual() || !Ids.ours(n.get("id").asText()) || !n.path("created").isTextual()
                || !n.path("generation").isIntegralNumber() || !n.get("generation").canConvertToLong() || n.get("generation").asLong() < 0) {
                return null;
            }
            return new Info(n.get("id").asText(), Instant.parse(n.get("created").asText()), n.get("generation").asLong());
        } catch (IOException | DateTimeParseException e) {
            return null;
        }
    }

    static long generation(Storage s) throws IOException {
        return current(s).generation();
    }

    /**
     * Makes the vault with its recovery code and this computer's slot. Two computers may do it at once: whoever reads back
     * another's {@link #INFO}, after writing its own or after writing its slots, takes its slots back and stops, leaving
     * the other's vault as it is. Any other failure, the vault's own file included (it may land with its answer lost),
     * takes back all it wrote, so no half-made vault is left in the way. A vault file that isn't one is replaced only
     * while no slot is there: nothing anyone could open.
     */
    static Created create(Storage s, String machine, String machineName) throws IOException, Exists {
        if (!Ids.ours(machine)) throw new IllegalArgumentException("Not a machine id");
        if (s.exists(INFO) && (info(s) != null || !s.list(SLOTS).isEmpty())) throw new Exists();
        Info mine = new Info(UUID.randomUUID().toString(), Instant.now(), 0);
        byte[] vk = VaultCrypto.randomKey();
        List<String> written = new ArrayList<>();
        boolean made = false;
        try {
            s.mkdirs(ROOT);
            s.put(INFO, json(mine));
            if (!stillOurs(s, mine)) throw new Exists();
            s.mkdirs(SLOTS);
            RecoveryCode code = writeRecovery(s, vk, mine, written);
            Computer computer = writeComputer(s, vk, mine, machine, machineName, written);
            if (!stillOurs(s, mine)) throw undo(s, written, null, new Exists());
            made = true;
            return new Created(vk, code, computer, mine);
        } catch (IOException e) {
            throw undo(s, written, mine, e);
        } catch (RuntimeException e) {
            throw undo(s, written, mine, e);
        } finally {
            if (!made) Arrays.fill(vk, (byte) 0);
        }
    }

    /** Every slot of the account's vault; a file that isn't one, of another vault, or whose name isn't its id, is skipped. */
    static List<VaultSlot> slots(Storage s) throws IOException {
        Info vault = info(s);
        List<VaultSlot> out = new ArrayList<>();
        if (vault == null) return out;
        for (String name : s.list(SLOTS)) {
            if (!name.endsWith(JSON_FILE)) continue;
            String id = name.substring(0, name.length() - JSON_FILE.length());
            VaultSlot slot = slot(s, vault, id);
            if (slot != null) out.add(slot);
        }
        return out;
    }

    /** The slot {@code id} of {@code vault}; null when there's none, it isn't one, or it is another vault's or another id's. */
    static VaultSlot slot(Storage s, Info vault, String id) throws IOException {
        if (!Ids.ours(id)) return null;
        byte[] json = s.get(path(id));
        VaultSlot slot = json == null ? null : VaultSlot.parse(json);
        return slot != null && slot.id().equals(id) && slot.vault().equals(vault.id()) ? slot : null;
    }

    /** The vault key from one slot and its way in's key. */
    static byte[] open(Storage s, VaultSlot slot, byte[] slotKey) throws VaultCrypto.Broken {
        return slot.unwrap(slotKey);
    }

    /** This computer's way in, as {@code machine}'s own slot (one per computer: a second replaces its first). */
    static Computer addComputer(Storage s, byte[] vk, String machine, String name) throws IOException {
        return writeComputer(s, vk, current(s), machine, name, new ArrayList<>());
    }

    /** At least {@link #MIN_PASSWORD} characters, as the person counts them (an emoji is one). */
    static VaultSlot addPassword(Storage s, byte[] vk, char[] password) throws IOException {
        if (Character.codePointCount(password, 0, password.length) < MIN_PASSWORD) {
            throw new IllegalArgumentException("A password needs at least " + MIN_PASSWORD + " characters");
        }
        Info vault = current(s);
        ObjectNode params = VaultSlot.newPasswordParams();
        byte[] key;
        try {
            key = VaultSlot.passwordKey(password, params);
        } catch (VaultCrypto.Broken e) {
            throw new IllegalStateException("Fresh password params were refused", e);
        }
        try {
            return write(s, VaultSlot.wrap(vault.id(), VaultSlot.Method.PASSWORD, "password", vault.generation(), key, params, vk), new ArrayList<>());
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * A new recovery code. Its slot is written first and every other recovery slot goes after, so there's always one; one
     * that can't be deleted now goes on the next call, and the new code is given anyway (it is already a way in).
     */
    static RecoveryCode newRecovery(Storage s, byte[] vk) throws IOException {
        List<String> written = new ArrayList<>();
        RecoveryCode code = writeRecovery(s, vk, current(s), written);
        try {
            for (VaultSlot slot : slots(s)) {
                if (slot.method() == VaultSlot.Method.RECOVERY && !written.contains(path(slot.id()))) removeSlot(s, slot.id());
            }
        } catch (IOException e) {
            // Left for the next call; the person must see the new code now, or it is lost.
        }
        return code;
    }

    /** Ids are ours (UUIDs): one from the account that isn't never becomes a path to delete. */
    static void removeSlot(Storage s, String id) throws IOException {
        if (!Ids.ours(id)) throw new IllegalArgumentException("Not a slot id");
        s.delete(path(id));
    }

    private static Info current(Storage s) throws IOException {
        Info info = info(s);
        if (info == null) throw new IOException(I18n.t("cloud.error.noVault"));
        return info;
    }

    private static boolean stillOurs(Storage s, Info mine) throws IOException {
        Info read = info(s);
        return read != null && read.id().equals(mine.id());
    }

    /**
     * Deletes the slots {@code written} and, when {@code mine} is given and the account's vault is still it, the vault's
     * file too; what can't be deleted is told with {@code cause}, which is returned to be thrown.
     */
    private static <E extends Exception> E undo(Storage s, List<String> written, Info mine, E cause) {
        for (String path : written) {
            try {
                s.delete(path);
            } catch (IOException e) {
                cause.addSuppressed(e);
            }
        }
        try {
            if (mine != null && stillOurs(s, mine)) s.delete(INFO);
        } catch (IOException e) {
            cause.addSuppressed(e);
        }
        return cause;
    }

    private static RecoveryCode writeRecovery(Storage s, byte[] vk, Info vault, List<String> written) throws IOException {
        RecoveryCode code = RecoveryCode.random();
        byte[] key = VaultSlot.recoveryKey(code);
        try {
            write(s, VaultSlot.wrap(vault.id(), VaultSlot.Method.RECOVERY, "recovery", vault.generation(), key, JSON.createObjectNode(), vk), written);
            return code;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static Computer writeComputer(Storage s, byte[] vk, Info vault, String machine, String name, List<String> written)
        throws IOException {
        if (!Ids.ours(machine)) throw new IllegalArgumentException("Not a machine id");
        KeyPair pair = x25519();
        ObjectNode params = JSON.createObjectNode();
        params.put("x25519", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        byte[] key = VaultCrypto.randomKey();
        write(s, VaultSlot.wrap(vault.id(), machine, VaultSlot.Method.COMPUTER, name, vault.generation(), key, params, vk), written);
        return new Computer(key, pair);
    }

    /** Notes the path before writing, so a write that fails halfway is taken back too. */
    private static VaultSlot write(Storage s, VaultSlot slot, List<String> written) throws IOException {
        written.add(path(slot.id()));
        s.put(path(slot.id()), slot.toJson());
        return slot;
    }

    private static KeyPair x25519() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
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

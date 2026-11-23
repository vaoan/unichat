package app.unichat.cloud;

import app.unichat.support.I18n;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.KeyPair;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The vault in the person's account: {@link #INFO} says there is one, and each way in is a slot of its own under
 * {@link #SLOTS}, so adding one never rewrites another and any of them opens the same vault key. Everything read from
 * the account is untrusted: what isn't a vault or a slot reads as none.
 */
final class Vault {
    static final String ROOT = CloudAccount.ROOT + "/Vault", SLOTS = ROOT + "/keys", INFO = ROOT + "/vault.json";
    static final int MIN_PASSWORD = 12;
    private static final String JSON_FILE = ".json", VOUCH = "vouch";
    private static final ObjectMapper JSON = new ObjectMapper();

    private Vault() {}

    /**
     * The vault's file: its id, when it was made, its generation, and, from a change of key on, what vouches for that
     * generation ({@link #vouched}: sealed with the key it replaced; null before any change, or when it isn't one).
     */
    @SuppressWarnings("ArrayRecordComponent") // the vouch is a seal; vaults compare ids and generations, never Info
    record Info(String id, Instant created, long generation, byte[] vouch) {
        Info(String id, Instant created, long generation) {
            this(id, created, generation, null);
        }

        @Override
        public String toString() {
            return "Vault.Info[" + id + ", " + generation + "]";
        }
    }

    /** A new vault: its key, the code to print (shown once) and this computer's way in. */
    @SuppressWarnings("ArrayRecordComponent") // key bytes, wiped in place; never compared
    record Created(byte[] vk, RecoveryCode code, Computer computer, Info info) {
        @Override
        public String toString() {
            return "Vault.Created[" + info.id() + "]";
        }
    }

    /** A computer's way in: the key its slot is wrapped with, and its X25519 pair (the public half is in the slot). */
    @SuppressWarnings("ArrayRecordComponent") // key bytes, wiped in place; never compared
    record Computer(byte[] key, KeyPair pair) {
        @Override
        public String toString() {
            return "Vault.Computer[…]";
        }
    }

    /**
     * The vault's new key, the new recovery code (shown once), the ways in that were dropped (they can't be wrapped again
     * without their secret: added again by the person), the files that opened with neither key (left as they were), the
     * computers offered nothing because their key isn't the one seen here (machine → its slot's label), and whether some
     * file couldn't be sealed again (each computer seals its own again as it takes the new key).
     */
    @SuppressWarnings("ArrayRecordComponent") // key bytes, wiped in place; never compared
    record Rotated(byte[] vk, RecoveryCode code, Set<VaultSlot.Method> dropped, List<String> left, Map<String, String> refused,
                   boolean resealFailed) {
        @Override
        public String toString() {
            return "Vault.Rotated[" + dropped + ", " + left.size() + " left, " + refused.keySet() + " refused]";
        }
    }

    /** There already is a vault in the account (perhaps another computer made it a moment ago): open it instead. */
    static final class Exists extends Exception {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Exists() {
            super("There already is a vault in the account");
        }
    }

    /** The vault in the account, or null when there's none (or what's there isn't one). */
    static Info info(Storage s) throws IOException {
        return parse(s.get(INFO));
    }

    /** The vault {@code json} says, or null when it says none (null) or isn't one. */
    static Info parse(byte[] json) {
        if (json == null) return null;
        try {
            JsonNode n = JSON.readTree(json);
            if (n == null || !n.path("id").isTextual() || !Ids.ours(n.get("id").asText()) || !n.path("created").isTextual()
                || !n.path("generation").isIntegralNumber() || !n.get("generation").canConvertToLong() || n.get("generation").asLong() < 0) {
                return null;
            }
            byte[] vouch = null;
            if (n.path(VOUCH).isTextual()) {
                try {
                    vouch = java.util.Base64.getDecoder().decode(n.get(VOUCH).asText());
                } catch (IllegalArgumentException e) {
                    vouch = null; // not one: nothing vouches for this generation
                }
            }
            return new Info(n.get("id").asText(), Instant.parse(n.get("created").asText()), n.get("generation").asLong(), vouch);
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
        if (!s.exists(INFO) && !s.list(ROOT).isEmpty()) s.delete(ROOT); // left from a vault turned off whose folder wouldn't go
        VaultOff.delete(s); // the last vault's "turned off" says nothing of this one
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

    /** The vault's computers: each machine with a computer slot, and its label (from the account: one plain line, {@link VaultView#label}). */
    static Map<String, String> computers(Storage s) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (VaultSlot slot : slots(s)) {
            if (slot.method() == VaultSlot.Method.COMPUTER) out.put(slot.id(), VaultView.label(slot.label()));
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
    static byte[] open(VaultSlot slot, byte[] slotKey) throws VaultCrypto.Broken {
        return slot.unwrap(slotKey);
    }

    /** This computer's way in, as {@code machine}'s own slot (one per computer: a second replaces its first). */
    static Computer addComputer(Storage s, byte[] vk, String machine, String name) throws IOException {
        return writeComputer(s, vk, current(s), machine, name, new ArrayList<>());
    }

    /** Whether {@code password} has at least {@link #MIN_PASSWORD} characters, as the person counts them (an emoji is one). */
    static boolean longEnough(char[] password) {
        return Character.codePointCount(password, 0, password.length) >= MIN_PASSWORD;
    }

    /** At least {@link #MIN_PASSWORD} characters ({@link #longEnough}). */
    static VaultSlot addPassword(Storage s, byte[] vk, char[] password) throws IOException {
        if (!longEnough(password)) {
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

    /**
     * A new vault key, so that whoever copied a slot and its secret before (a computer removed, a way in taken away) reads
     * nothing new. Every computer still listed whose public key {@code oldVk} sealed, and is the one {@code peers} first saw
     * (one never seen is pinned now), is offered it, sealed for its X25519 key ({@link VaultNext}), and takes it on its
     * next round (this one too, right after); any other is offered nothing and named; a new recovery code; the
     * generation rises; then every other way in (the old recovery code, the password) goes, and every file is sealed again.
     * Until the generation rises the old key and every old way in stay as they were, and a change cut short takes back what
     * it wrote ({@link #withdrawn}). After it,
     * nothing fails the change: the person must see the new code, and what's left is done again as each computer takes the
     * new key.
     */
    static Rotated rotate(Storage s, byte[] oldVk, String machine, VaultPeers peers) throws IOException {
        return rotate(s, oldVk, machine, peers, id -> true, () -> { });
    }

    /**
     * The same, offering the new key only to the computers {@code confirmed} (the person saw them listed and said yes) and
     * this one: any other, one that came after the list was shown included, is offered nothing and named. {@code rising}
     * runs right before the generation rises (whatever the account answers then, the new code may be the vault's).
     */
    static Rotated rotate(Storage s, byte[] oldVk, String machine, VaultPeers peers, java.util.function.Predicate<String> confirmed,
                          Runnable rising) throws IOException {
        if (!Ids.ours(machine)) throw new IllegalArgumentException("Not a machine id");
        Info was = current(s);
        List<VaultSlot> slots = slots(s);
        VaultSlot own = slots.stream().filter(slot -> slot.id().equals(machine) && slot.method() == VaultSlot.Method.COMPUTER)
            .findFirst().orElse(null);
        byte[] ownKey = own == null ? null : VaultNext.publicKey(own, oldVk);
        if (ownKey == null || !peers.trusts(own, ownKey)) throw new IOException(I18n.t("cloud.error.vaultNotOpen"));
        Info now = new Info(was.id(), was.created(), was.generation() + 1, null);
        now = new Info(now.id(), now.created(), now.generation(), VaultCrypto.seal(oldVk, vouchPath(now), vouched(now)));
        byte[] vk = VaultCrypto.randomKey();
        List<String> written = new ArrayList<>();
        List<VaultSlot> offered = new ArrayList<>();
        Map<String, String> refused = new TreeMap<>();
        RecoveryCode code = null;
        try {
            for (VaultSlot slot : slots) {
                if (slot.method() != VaultSlot.Method.COMPUTER) continue;
                boolean asked = slot.id().equals(machine) || confirmed.test(slot.id());
                if (asked && offer(s, slot, oldVk, vk, now.generation(), peers)) offered.add(slot);
                else refused.put(slot.id(), VaultView.label(slot.label()));
            }
            code = writeRecovery(s, vk, now, written);
            rising.run();
            s.put(INFO, json(now));
        } catch (IOException e) {
            if (code == null || !at(s, now)) throw withdrawn(s, was, offered, written, vk, e); // risen with its answer lost: done
        } catch (RuntimeException e) {
            throw withdrawn(s, was, offered, written, vk, e);
        }
        try {
            Set<VaultSlot.Method> dropped = EnumSet.noneOf(VaultSlot.Method.class);
            for (VaultSlot slot : slots) {
                if (slot.method() == VaultSlot.Method.COMPUTER || written.contains(path(slot.id()))) continue;
                if (slot.method() != VaultSlot.Method.RECOVERY) dropped.add(slot.method());
                try {
                    removeSlot(s, slot.id());
                } catch (IOException e) {
                    // It opens only the old key now; the next change or new code takes it.
                }
            }
            VaultReseal.Result resealed = VaultReseal.run(s, oldVk, vk);
            return new Rotated(vk, code, dropped, resealed.left(), refused, !resealed.failed().isEmpty());
        } catch (RuntimeException e) {
            Arrays.fill(vk, (byte) 0);
            throw e;
        }
    }

    /**
     * A change cut short before the generation rose: its offers and new recovery slot taken back, so an offer left there
     * never becomes the vault's key if someone raises the generation by hand (the person's recovery code would open only
     * the old one). When the account can't say the generation is still the old one, they stay: it may have risen.
     */
    private static <E extends Exception> E withdrawn(Storage s, Info was, List<VaultSlot> offered, List<String> written, byte[] vk, E cause) {
        Arrays.fill(vk, (byte) 0);
        if (!at(s, was)) return cause;
        for (VaultSlot slot : offered) {
            try {
                s.put(path(slot.id()), slot.toJson());
            } catch (IOException e) {
                cause.addSuppressed(e);
            }
        }
        return undo(s, written, null, cause);
    }

    /**
     * Whether {@code vault}'s generation is vouched for with {@code held}: its file says so, sealed with that key by the
     * change, or some computer is offered it by one who held that key ({@link VaultNext#vouched}). A generation raised
     * through the account alone never is.
     */
    static boolean vouched(Storage s, Info vault, byte[] held) throws IOException {
        if (vault.vouch() != null) {
            try {
                if (java.security.MessageDigest.isEqual(vouched(vault), VaultCrypto.open(held, vouchPath(vault), vault.vouch()))) return true;
            } catch (VaultCrypto.Broken e) {
                // Not this key's: an offer may still say so.
            }
        }
        for (VaultSlot slot : slots(s)) {
            if (slot.method() == VaultSlot.Method.COMPUTER && VaultNext.vouched(slot, held, vault.generation())) return true;
        }
        return false;
    }

    /** Whether the account's vault is {@code info}'s, at its generation; false when it can't be read. */
    static boolean at(Storage s, Info info) {
        try {
            Info read = info(s);
            return read != null && read.id().equals(info.id()) && read.generation() == info.generation();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@code slot}'s computer offered {@code vk}; its own key and wrapping stay until it takes it. A slot whose public key
     * the vault key didn't seal (put in through the account), isn't the one first seen here, or isn't a key one can agree
     * with (malformed, of small order) is offered nothing (false).
     */
    private static boolean offer(Storage s, VaultSlot slot, byte[] oldVk, byte[] vk, long generation, VaultPeers peers)
        throws IOException {
        byte[] publicKey = VaultNext.publicKey(slot, oldVk);
        if (publicKey == null || !peers.trusts(slot, publicKey)) return false;
        ObjectNode params = slot.params().deepCopy();
        try {
            VaultNext.offer(params, publicKey, slot.vault(), slot.id(), generation, vk, oldVk);
        } catch (IllegalArgumentException e) {
            return false;
        }
        s.put(path(slot.id()), new VaultSlot(slot.id(), slot.vault(), slot.method(), slot.label(), slot.created(), slot.generation(),
            params, slot.wrapped()).toJson());
        return true;
    }

    /**
     * This computer's slot wrapped again with its own key for {@code vk}, at the vault's generation, no offer left, showing
     * {@code publicKey} (its own, from its private key) sealed with {@code vk}.
     */
    static void rewrap(Storage s, Info vault, VaultSlot own, byte[] slotKey, byte[] vk, byte[] publicKey) throws IOException {
        if (publicKey == null) throw new IOException(I18n.t("cloud.error.vaultNotOpen"));
        ObjectNode params = own.params().deepCopy();
        params.remove(VaultNext.NEXT);
        VaultNext.bind(params, vk, vault.id(), own.id(), publicKey);
        write(s, VaultSlot.wrap(vault.id(), own.id(), VaultSlot.Method.COMPUTER, own.label(), vault.generation(), slotKey, params, vk),
            new ArrayList<>());
    }

    /** Another computer's way into {@code vault} taken away: only a computer's slot, by its machine id; none there is fine. */
    static void removeComputer(Storage s, Info vault, String machine) throws IOException {
        if (!Ids.ours(machine)) throw new IllegalArgumentException("Not a machine id");
        VaultSlot slot = slot(s, vault, machine);
        if (slot != null && slot.method() == VaultSlot.Method.COMPUTER) removeSlot(s, machine);
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
        KeyPair pair = VaultNext.generate();
        ObjectNode params = JSON.createObjectNode();
        VaultNext.bind(params, vk, vault.id(), machine, pair.getPublic().getEncoded());
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

    private static String vouchPath(Info info) {
        return "generation:" + info.id() + ":" + info.generation();
    }

    private static byte[] vouched(Info info) {
        return (info.id() + ":" + info.generation()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String path(String id) {
        return SLOTS + "/" + id + JSON_FILE;
    }

    /** The vault's file (its being under the English names is what says the vault moved there: {@link VaultNames}). */
    static byte[] json(Info info) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", info.id()).put("created", info.created().toString()).put("generation", info.generation());
        if (info.vouch() != null) n.put(VOUCH, java.util.Base64.getEncoder().encodeToString(info.vouch()));
        try {
            return JSON.writeValueAsBytes(n);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

package app.unichat.cloud;

import app.unichat.settings.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The other computers' public keys as this computer first saw them in the vault (machine-only, {@link #KEY}): a key
 * changed after that, even sealed with the vault key (a computer removed still holds it until the key changes), is
 * offered no new vault key, and is named, until the person accepts it. A pin stays when its slot is gone (it may be put
 * back with another key); only removing that computer here drops it, and a computer removed here is never pinned nor
 * offered again. A slot that isn't a computer's is noted as such, so it isn't read again.
 */
final class VaultPeers {
    static final String KEY = "cloud.vault.peers";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOT_A_COMPUTER = "";

    private final String vault;
    /** Slot id → its public key in base64, or {@link #NOT_A_COMPUTER}. */
    private final Map<String, String> pins;
    /** The computers removed here. */
    private final Set<String> removed;
    /** Slot id → when it was first pinned here (unknown for pins older than this record). */
    private final Map<String, String> seen;
    /** Slot id → how many computers had been removed here when it was pinned: one pinned after the last is new since. */
    private final Map<String, Integer> after;
    /** How many computers were removed here. */
    private int removals;

    private VaultPeers(String vault, Map<String, String> pins, Set<String> removed, Map<String, String> seen, Map<String, Integer> after,
                       int removals) {
        this.vault = vault;
        this.pins = pins;
        this.removed = removed;
        this.seen = seen;
        this.after = after;
        this.removals = removals;
    }

    /** What this computer saw of {@code vault}; nothing when what it holds is another vault's or none. */
    static VaultPeers load(String vault) {
        Map<String, String> pins = new TreeMap<>(), seen = new TreeMap<>();
        Map<String, Integer> after = new TreeMap<>();
        Set<String> removed = new TreeSet<>();
        int removals = 0;
        String saved = Settings.get().getString(KEY, null);
        if (saved != null) {
            try {
                JsonNode n = JSON.readTree(saved);
                if (n != null && vault.equals(n.path("vault").asText()) && n.path("pins").isObject()) {
                    n.get("pins").fields().forEachRemaining(e -> { if (e.getValue().isTextual()) pins.put(e.getKey(), e.getValue().asText()); });
                    n.path("removed").forEach(id -> { if (id.isTextual()) removed.add(id.asText()); });
                    n.path("seen").fields().forEachRemaining(e -> { if (e.getValue().isTextual()) seen.put(e.getKey(), e.getValue().asText()); });
                    n.path("after").fields().forEachRemaining(e -> { if (e.getValue().isInt()) after.put(e.getKey(), e.getValue().asInt()); });
                    removals = Math.max(0, n.path("removals").asInt(0));
                }
            } catch (IOException e) {
                // Not ours: seen afresh.
            }
        }
        return new VaultPeers(vault, pins, removed, seen, after, removals);
    }

    void save() {
        ObjectNode n = JSON.createObjectNode().put("vault", vault).put("removals", removals);
        ObjectNode p = n.putObject("pins");
        pins.forEach(p::put);
        removed.forEach(n.putArray("removed")::add);
        ObjectNode at = n.putObject("seen");
        seen.forEach(at::put);
        ObjectNode epoch = n.putObject("after");
        after.forEach(epoch::put);
        Settings.get().set(KEY, n.toString());
    }

    static void forget() {
        Settings.get().set(KEY, null);
    }

    /**
     * Whether {@code slot}'s public key is the one first seen here: the one pinned, or, never seen, this one (now pinned).
     * {@code publicKey} has already been checked against the vault key.
     */
    boolean trusts(VaultSlot slot, byte[] publicKey) {
        if (removed.contains(slot.id())) return false;
        String pinned = pins.get(slot.id());
        if (pinned == null || pinned.equals(NOT_A_COMPUTER)) {
            pin(slot.id(), publicKey);
            return true;
        }
        return Arrays.equals(Base64.getDecoder().decode(pinned), publicKey);
    }

    /** {@code machine} removed here: its pin dropped, and it's never pinned nor offered a key again (until accepted). */
    void removed(String machine) {
        pins.remove(machine);
        seen.remove(machine);
        after.remove(machine);
        removed.add(machine);
        removals++;
    }

    /** The person says {@code publicKey} is {@code machine}'s: pinned in place of what was, offered again. */
    void accept(String machine, byte[] publicKey) {
        removed.remove(machine);
        pin(machine, publicKey);
    }

    private void pin(String machine, byte[] publicKey) {
        pins.put(machine, Base64.getEncoder().encodeToString(publicKey));
        seen.put(machine, Instant.now().toString());
        after.put(machine, removals);
    }

    /** When {@code machine} was first pinned here; null when unknown (never, or pinned before this was kept). */
    Instant firstSeen(String machine) {
        try {
            String at = seen.get(machine);
            return at == null ? null : Instant.parse(at);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Whether the next key would be refused to {@code machine}, whose slot shows {@code publicKey} (null: none the vault
     * key sealed): removed here, or another key than the one pinned.
     */
    boolean keyChanged(String machine, byte[] publicKey) {
        if (removed.contains(machine) || publicKey == null) return true;
        String pinned = pins.get(machine);
        return pinned != null && !pinned.equals(NOT_A_COMPUTER) && !Arrays.equals(Base64.getDecoder().decode(pinned), publicKey);
    }

    /** Whether {@code machine} came after the last computer removed here (never seen yet, or pinned after it). */
    boolean newSinceRemoval(String machine) {
        if (removals == 0) return false;
        Integer at = after.get(machine);
        return at == null ? !pins.containsKey(machine) || pins.get(machine).equals(NOT_A_COMPUTER) : at >= removals;
    }

    /**
     * Each round with the vault open: every computer's slot not seen yet (nor removed here) is pinned, if {@code vk} sealed
     * its key. Only slots not seen are read.
     */
    static void see(Storage s, Vault.Info info, byte[] vk) throws IOException {
        VaultPeers peers = load(info.id());
        for (String name : s.list(Vault.SLOTS)) {
            if (!name.endsWith(".json")) continue;
            String id = name.substring(0, name.length() - ".json".length());
            if (!Ids.ours(id)) continue;
            if (peers.pins.containsKey(id) || peers.removed.contains(id)) continue;
            VaultSlot slot = Vault.slot(s, info, id);
            if (slot == null) continue;
            if (slot.method() != VaultSlot.Method.COMPUTER) {
                peers.pins.put(id, NOT_A_COMPUTER);
                continue;
            }
            byte[] key = VaultNext.publicKey(slot, vk);
            if (key != null) peers.trusts(slot, key);
        }
        peers.save();
    }
}

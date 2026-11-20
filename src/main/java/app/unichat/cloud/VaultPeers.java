package app.unichat.cloud;

import app.unichat.settings.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
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

    private VaultPeers(String vault, Map<String, String> pins, Set<String> removed) {
        this.vault = vault;
        this.pins = pins;
        this.removed = removed;
    }

    /** What this computer saw of {@code vault}; nothing when what it holds is another vault's or none. */
    static VaultPeers load(String vault) {
        Map<String, String> pins = new TreeMap<>();
        Set<String> removed = new TreeSet<>();
        String saved = Settings.get().getString(KEY, null);
        if (saved != null) {
            try {
                JsonNode n = JSON.readTree(saved);
                if (n != null && vault.equals(n.path("vault").asText()) && n.path("pins").isObject()) {
                    n.get("pins").fields().forEachRemaining(e -> { if (e.getValue().isTextual()) pins.put(e.getKey(), e.getValue().asText()); });
                    n.path("removed").forEach(id -> { if (id.isTextual()) removed.add(id.asText()); });
                }
            } catch (IOException e) {
                // Not ours: seen afresh.
            }
        }
        return new VaultPeers(vault, pins, removed);
    }

    void save() {
        ObjectNode n = JSON.createObjectNode().put("vault", vault);
        ObjectNode p = n.putObject("pins");
        pins.forEach(p::put);
        removed.forEach(n.putArray("removed")::add);
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
            pins.put(slot.id(), Base64.getEncoder().encodeToString(publicKey));
            return true;
        }
        return Arrays.equals(Base64.getDecoder().decode(pinned), publicKey);
    }

    /** {@code machine} removed here: its pin dropped, and it's never pinned nor offered a key again (until accepted). */
    void removed(String machine) {
        pins.remove(machine);
        removed.add(machine);
    }

    /** The person says {@code publicKey} is {@code machine}'s: pinned in place of what was, offered again. */
    void accept(String machine, byte[] publicKey) {
        removed.remove(machine);
        pins.put(machine, Base64.getEncoder().encodeToString(publicKey));
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

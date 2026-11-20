package app.unichat.cloud;

import app.unichat.settings.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * What says the vault was turned off, not just its file deleted: {@link #FILE}, outside the vault's folder, names the
 * vault and its generation, sealed with a key made from that generation's vault key. Each computer keeps that key while
 * the vault is open there ({@link #KEY}, machine-only), so one finding no {@code boveda.json} goes back to plain only
 * when the mark opens with it; someone with only the account can delete the vault's file but never write the mark.
 */
final class VaultOff {
    static final String FILE = CloudAccount.ROOT + "/Ajustes/boveda-apagada.json";
    /** The key the mark is sealed with, for the vault open here last (machine-only): "<vault>:<generation>:<key>". */
    static final String KEY = "cloud.vault.offKey";
    private static final String INFO = "unichat-vault off";
    private static final ObjectMapper JSON = new ObjectMapper();

    private VaultOff() {}

    /** The vault open here this round: its mark's key kept, so its turning off elsewhere can be believed later. */
    static void remember(Vault.Info vault, byte[] vk) {
        byte[] key = VaultCrypto.hkdf(vk, INFO);
        try {
            String kept = vault.id() + ":" + vault.generation() + ":" + Base64.getEncoder().encodeToString(key);
            Settings s = Settings.get();
            if (!kept.equals(s.getString(KEY, null))) s.set(KEY, kept);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The mark that {@code vault} is turned off, written before its folder is deleted. */
    static void write(Storage plain, Vault.Info vault, byte[] vk) throws IOException {
        byte[] key = VaultCrypto.hkdf(vk, INFO);
        try {
            byte[] mac = VaultCrypto.seal(key, path(vault.id(), vault.generation()), said(vault.id(), vault.generation()));
            plain.mkdirs(FILE.substring(0, FILE.lastIndexOf('/')));
            plain.put(FILE, JSON.writeValueAsBytes(JSON.createObjectNode().put("vault", vault.id()).put("generation", vault.generation())
                .put("mac", Base64.getEncoder().encodeToString(mac))));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Whether the account's mark says {@code vault} was turned off, sealed with the key kept here; false for anything else. */
    static boolean says(Storage plain, String vault) throws IOException {
        String kept = Settings.get().getString(KEY, null);
        byte[] file = plain.get(FILE);
        if (kept == null || file == null) return false;
        String[] parts = kept.split(":", 3);
        if (parts.length != 3 || !parts[0].equals(vault)) return false;
        byte[] key = null;
        try {
            JsonNode n = JSON.readTree(file);
            if (n == null || !vault.equals(n.path("vault").asText(null)) || !n.path("generation").canConvertToLong()
                || !String.valueOf(n.get("generation").asLong()).equals(parts[1]) || !n.path("mac").isTextual()) {
                return false;
            }
            long generation = n.get("generation").asLong();
            key = Base64.getDecoder().decode(parts[2]);
            byte[] opened = VaultCrypto.open(key, path(vault, generation), Base64.getDecoder().decode(n.get("mac").asText()));
            return MessageDigest.isEqual(said(vault, generation), opened);
        } catch (IOException | IllegalArgumentException | VaultCrypto.Broken e) {
            return false;
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    /** The mark deleted: a new vault is there, or the old one wasn't all gone. */
    static void delete(Storage plain) throws IOException {
        plain.delete(FILE);
    }

    private static String path(String vault, long generation) {
        return "off:" + vault + ":" + generation;
    }

    private static byte[] said(String vault, long generation) {
        return (vault + ":" + generation).getBytes(StandardCharsets.UTF_8);
    }
}

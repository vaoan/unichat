package app.unichat.cloud;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.KeyAgreement;

/**
 * A new vault key offered to a computer that isn't running, in its slot's params: only its X25519 private key (machine-only)
 * opens it, so knowing the old vault key opens nothing new. The computer's public key in its slot is sealed with the vault
 * key too ({@link #PUBLIC_SEALED}): someone who can only write to the account can't put in a key of theirs to be offered
 * the next one. An offer seals it with the new key as well, so a change cut short leaves the old seal as it was and a
 * second change before the computer took the first still trusts it.
 */
final class VaultNext {
    static final String PUBLIC = "x25519", PUBLIC_SEALED = "x25519Sealed", NEXT = "next";
    private static final String EPHEMERAL = "x25519", SEALED = "sealed", INFO = "unichat-vault rotate";

    private VaultNext() {}

    /** The computer's public key, sealed with {@code vk} beside it as it is, so whoever holds the vault key can trust it. */
    static void bind(ObjectNode params, byte[] vk, String vault, String id, byte[] publicKey) {
        params.put(PUBLIC, Base64.getEncoder().encodeToString(publicKey));
        params.put(PUBLIC_SEALED, Base64.getEncoder().encodeToString(VaultCrypto.seal(vk, publicPath(vault, id), publicKey)));
    }

    /** The slot's public key when {@code vk} sealed it for that vault and id (in the slot or its offer), or null: one put in by anyone else. */
    static byte[] publicKey(VaultSlot slot, byte[] vk) {
        byte[] shown = bytes(slot.params(), PUBLIC);
        if (shown == null) return null;
        byte[] mine = opened(vk, slot, bytes(slot.params(), PUBLIC_SEALED));
        if (mine == null && slot.params().get(NEXT) instanceof ObjectNode next) mine = opened(vk, slot, bytes(next, PUBLIC_SEALED));
        return mine != null && Arrays.equals(mine, shown) ? mine : null;
    }

    private static byte[] opened(byte[] vk, VaultSlot slot, byte[] sealed) {
        if (sealed == null) return null;
        try {
            return VaultCrypto.open(vk, publicPath(slot.vault(), slot.id()), sealed);
        } catch (VaultCrypto.Broken e) {
            return null;
        }
    }

    /** {@code newVk} sealed for {@code publicKey}'s computer, as {@link #NEXT} in {@code params}, for {@code generation}. */
    static void offer(ObjectNode params, byte[] publicKey, String vault, String id, long generation, byte[] newVk) {
        KeyPair ephemeral = generate();
        byte[] key = key(ephemeral.getPrivate(), decodePublic(publicKey));
        try {
            ObjectNode next = params.putObject(NEXT);
            next.put(EPHEMERAL, Base64.getEncoder().encodeToString(ephemeral.getPublic().getEncoded()));
            next.put(SEALED, Base64.getEncoder().encodeToString(VaultCrypto.seal(key, nextPath(vault, id, generation), newVk)));
            next.put(PUBLIC_SEALED, Base64.getEncoder().encodeToString(VaultCrypto.seal(newVk, publicPath(vault, id), publicKey)));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The new vault key offered in {@code slot} for {@code generation}, opened with this computer's private key. */
    static byte[] take(VaultSlot slot, byte[] privateKey, long generation) throws VaultCrypto.Broken {
        if (!(slot.params().get(NEXT) instanceof ObjectNode next)) throw new VaultCrypto.Broken();
        byte[] ephemeral = bytes(next, EPHEMERAL), sealed = bytes(next, SEALED);
        if (ephemeral == null || sealed == null) throw new VaultCrypto.Broken();
        byte[] key;
        try {
            key = key(KeyFactory.getInstance("X25519").generatePrivate(new PKCS8EncodedKeySpec(privateKey)), decodePublic(ephemeral));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new VaultCrypto.Broken();
        }
        try {
            return VaultCrypto.open(key, nextPath(slot.vault(), slot.id(), generation), sealed);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A public key from the account that isn't one is as broken as a wrong key. */
    private static PublicKey decodePublic(byte[] encoded) {
        try {
            return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not an X25519 public key");
        }
    }

    private static byte[] key(PrivateKey mine, PublicKey theirs) {
        byte[] shared = null;
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("X25519");
            agreement.init(mine);
            agreement.doPhase(theirs, true);
            shared = agreement.generateSecret();
            return VaultCrypto.hkdf(shared, INFO);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("The keys don't agree");
        } finally {
            if (shared != null) Arrays.fill(shared, (byte) 0);
        }
    }

    private static byte[] bytes(ObjectNode n, String field) {
        if (!n.path(field).isTextual()) return null;
        try {
            return Base64.getDecoder().decode(n.get(field).asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String publicPath(String vault, String id) {
        return "x25519:" + vault + ":" + id;
    }

    private static String nextPath(String vault, String id, long generation) {
        return "next:" + vault + ":" + id + ":" + generation;
    }
}

package app.unichat.cloud;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.KeyAgreement;

/**
 * A new vault key offered to a computer that isn't running, in its slot's params: only its X25519 private key (machine-only)
 * opens it, so knowing the old vault key opens nothing new. The computer's public key in its slot is sealed with the vault
 * key too ({@link #PUBLIC_SEALED}): someone who can only write to the account can't put in a key of theirs to be offered
 * the next one. An offer seals it with the new key as well, so a change cut short leaves the old seal as it was and a
 * second change before the computer took the first still trusts it.
 * <p>
 * Every offer is vouched for with the key it replaces ({@link #AUTH}: its ephemeral key and what it seals, sealed with
 * the old vault key), so someone with only the account can't offer a key of their own: the computer takes it only when
 * the key it holds opens that. An offer to a computer that hasn't taken the one before keeps it ({@link #PRIOR}), so a
 * computer off through two changes follows the chain from the key it holds.
 */
final class VaultNext {
    static final String PUBLIC = "x25519";
    static final String PUBLIC_SEALED = "x25519Sealed";
    static final String NEXT = "next";
    private static final String EPHEMERAL = "x25519";
    private static final String SEALED = "sealed";
    private static final String INFO = "unichat-vault rotate";
    private static final String AUTH = "auth";
    private static final String PRIOR = "prior";
    private static final String GENERATION = "generation";
    /** Changes followed back from an offer at most: a chain from the account is untrusted. */
    private static final int CHAIN = 64;

    private VaultNext() {}

    /** The computer's public key, sealed with {@code vk} beside it as it is, so whoever holds the vault key can trust it. */
    static void bind(ObjectNode params, byte[] vk, String vault, String id, byte[] publicKey) {
        params.put(PUBLIC, Base64.getEncoder().encodeToString(publicKey));
        params.put(PUBLIC_SEALED, Base64.getEncoder().encodeToString(VaultCrypto.seal(vk, publicPath(vault, id), publicKey)));
    }

    /** The slot's public key when {@code vk} sealed it for that vault and id (in the slot or its offer), or null: one put in by anyone else. */
    static byte @Nullable [] publicKey(VaultSlot slot, byte[] vk) {
        byte[] shown = bytes(slot.params(), PUBLIC);
        if (shown == null) return null;
        byte[] mine = opened(vk, slot, bytes(slot.params(), PUBLIC_SEALED));
        if (mine == null && slot.params().get(NEXT) instanceof ObjectNode next) mine = opened(vk, slot, bytes(next, PUBLIC_SEALED));
        return mine != null && Arrays.equals(mine, shown) ? mine : null;
    }

    private static byte @Nullable [] opened(byte[] vk, VaultSlot slot, byte @Nullable [] sealed) {
        if (sealed == null) return null;
        try {
            return VaultCrypto.open(vk, publicPath(slot.vault(), slot.id()), sealed);
        } catch (VaultCrypto.Broken e) {
            return null;
        }
    }

    /**
     * {@code newVk} sealed for {@code publicKey}'s computer, as {@link #NEXT} in {@code params}, for {@code generation},
     * vouched for with {@code oldVk}; an offer it hadn't taken yet kept behind it.
     */
    static void offer(ObjectNode params, byte[] publicKey, String vault, String id, long generation, byte[] newVk, byte[] oldVk) {
        KeyPair ephemeral = generate();
        byte[] mine = ephemeral.getPublic().getEncoded();
        byte[] key = key(ephemeral.getPrivate(), decodePublic(publicKey), mine, publicKey);
        try {
            ObjectNode prior = params.get(NEXT) instanceof ObjectNode was ? was : null;
            byte[] sealed = VaultCrypto.seal(key, nextPath(vault, id, generation), newVk);
            ObjectNode next = params.putObject(NEXT);
            next.put(EPHEMERAL, Base64.getEncoder().encodeToString(mine));
            next.put(SEALED, Base64.getEncoder().encodeToString(sealed));
            next.put(PUBLIC_SEALED, Base64.getEncoder().encodeToString(VaultCrypto.seal(newVk, publicPath(vault, id), publicKey)));
            next.put(GENERATION, generation);
            next.put(AUTH, Base64.getEncoder().encodeToString(VaultCrypto.seal(oldVk, offerPath(vault, id, generation), vouching(mine, sealed))));
            if (prior != null) next.set(PRIOR, prior);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * The new vault key offered in {@code slot} for {@code generation}, opened with this computer's private key, only
     * when {@code held} (the key it has) vouches for the offer, itself or through the offers before it.
     */
    static byte[] take(VaultSlot slot, byte[] privateKey, long generation, byte[] held) throws VaultCrypto.Broken {
        if (!(slot.params().get(NEXT) instanceof ObjectNode next)) throw new VaultCrypto.Broken();
        byte[] mine = publicOf(privateKey);
        if (mine == null) throw new VaultCrypto.Broken();
        PrivateKey own;
        try {
            own = KeyFactory.getInstance("X25519").generatePrivate(new PKCS8EncodedKeySpec(privateKey));
        } catch (GeneralSecurityException | IllegalArgumentException _) {
            throw new VaultCrypto.Broken();
        }
        return taken(slot, next, own, mine, generation, held, CHAIN);
    }

    /** Whether {@code slot}'s offer for {@code generation} is vouched for with {@code oldVk} itself: the change is real. */
    static boolean vouched(VaultSlot slot, byte[] oldVk, long generation) {
        return slot.params().get(NEXT) instanceof ObjectNode next && vouches(next, slot, generation, oldVk);
    }

    private static byte[] taken(VaultSlot slot, ObjectNode next, PrivateKey own, byte[] mine, long generation, byte[] held, int depth)
        throws VaultCrypto.Broken {
        byte[] ephemeral = bytes(next, EPHEMERAL);
        byte[] sealed = bytes(next, SEALED);
        if (ephemeral == null || sealed == null) throw new VaultCrypto.Broken();
        if (!vouches(next, slot, generation, held)) {
            // Vouched for with a key this computer never took: the offer before it, if it's that key's.
            if (depth == 0 || !(next.get(PRIOR) instanceof ObjectNode prior) || !prior.path(GENERATION).canConvertToLong()
                || prior.get(GENERATION).asLong() >= generation) {
                throw new VaultCrypto.Broken();
            }
            byte[] between = taken(slot, prior, own, mine, prior.get(GENERATION).asLong(), held, depth - 1);
            try {
                if (!vouches(next, slot, generation, between)) throw new VaultCrypto.Broken();
            } finally {
                Arrays.fill(between, (byte) 0);
            }
        }
        byte[] key;
        try {
            key = key(own, decodePublic(ephemeral), ephemeral, mine);
        } catch (IllegalArgumentException _) {
            throw new VaultCrypto.Broken();
        }
        try {
            return VaultCrypto.open(key, nextPath(slot.vault(), slot.id(), generation), sealed);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Whether {@code next}'s vouching opens with {@code oldVk} and names this very offer (its ephemeral key, what it seals). */
    private static boolean vouches(ObjectNode next, VaultSlot slot, long generation, byte[] oldVk) {
        byte[] ephemeral = bytes(next, EPHEMERAL);
        byte[] sealed = bytes(next, SEALED);
        byte[] auth = bytes(next, AUTH);
        if (ephemeral == null || sealed == null || auth == null) return false;
        try {
            return MessageDigest.isEqual(vouching(ephemeral, sealed), VaultCrypto.open(oldVk, offerPath(slot.vault(), slot.id(), generation), auth));
        } catch (VaultCrypto.Broken e) {
            return false;
        }
    }

    /** What an offer's vouching seals: its ephemeral key, then the SHA-256 of what it seals. */
    private static byte[] vouching(byte[] ephemeral, byte[] sealed) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sealed);
            byte[] out = Arrays.copyOf(ephemeral, ephemeral.length + hash.length);
            System.arraycopy(hash, 0, out, ephemeral.length, hash.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The public half of an X25519 private key (PKCS #8), as X.509: its agreement with the base point; null when it isn't one. */
    static byte @Nullable [] publicOf(byte[] privateKey) {
        try {
            KeyFactory keys = KeyFactory.getInstance("X25519");
            KeyAgreement agreement = KeyAgreement.getInstance("X25519");
            agreement.init(keys.generatePrivate(new PKCS8EncodedKeySpec(privateKey)));
            agreement.doPhase(keys.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.valueOf(9))), true);
            byte[] u = agreement.generateSecret(); // little-endian, as RFC 7748 encodes it
            byte[] bigEndian = new byte[u.length];
            for (int i = 0; i < u.length; i++) bigEndian[i] = u[u.length - 1 - i];
            return keys.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(1, bigEndian))).getEncoded();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
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
            throw new IllegalArgumentException("Not an X25519 public key", e);
        }
    }

    /** Bound to both public keys (ephemeral, then the computer's), so the key is of this one offer to this one computer. */
    private static byte[] key(PrivateKey mine, PublicKey theirs, byte[] ephemeral, byte[] recipient) {
        byte[] shared = null;
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("X25519");
            agreement.init(mine);
            agreement.doPhase(theirs, true);
            shared = agreement.generateSecret();
            byte[] label = INFO.getBytes(StandardCharsets.UTF_8);
            byte[] info = new byte[label.length + ephemeral.length + recipient.length];
            System.arraycopy(label, 0, info, 0, label.length);
            System.arraycopy(ephemeral, 0, info, label.length, ephemeral.length);
            System.arraycopy(recipient, 0, info, label.length + ephemeral.length, recipient.length);
            return VaultCrypto.hkdf(shared, info);
        } catch (GeneralSecurityException | IllegalStateException e) {
            throw new IllegalArgumentException("The keys don't agree", e);
        } finally {
            if (shared != null) Arrays.fill(shared, (byte) 0);
        }
    }

    private static byte @Nullable [] bytes(ObjectNode n, String field) {
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

    private static String offerPath(String vault, String id, long generation) {
        return "offer:" + vault + ":" + id + ":" + generation;
    }
}

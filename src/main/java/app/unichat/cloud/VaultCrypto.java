package app.unichat.cloud;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * The vault's cryptography: AES-256-GCM sealing bound to a logical path, HKDF-SHA-256 and Argon2id. Nothing here logs
 * or says in a message anything about a key, a password or a plain text.
 */
final class VaultCrypto {
    /** Sealed form: version | nonce | ciphertext and tag. */
    private static final byte VERSION = 1;
    private static final String AAD_PREFIX = "unichat-vault/" + VERSION + ":";
    private static final int KEY = 32, SALT = 16, NONCE = 12, TAG_BITS = 128, ARGON_KIB = 64 * 1024, ARGON_ITERATIONS = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VaultCrypto() {}

    /** Wrong key, another path, a changed byte or a version we don't know: the same answer, so nothing tells which. */
    static final class Broken extends Exception {
        Broken() {
            super("The vault's data could not be opened");
        }
    }

    static byte[] randomKey() {
        byte[] key = new byte[KEY];
        RANDOM.nextBytes(key);
        return key;
    }

    /** The path is authenticated, not stored: a file copied under another name no longer opens. */
    static byte[] seal(byte[] key, String path, byte[] plain) {
        if (key.length != KEY) throw new IllegalArgumentException("The vault's key must be 32 bytes");
        try {
            byte[] nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(path));
            byte[] body = c.doFinal(plain);
            byte[] out = new byte[1 + NONCE + body.length];
            out[0] = VERSION;
            System.arraycopy(nonce, 0, out, 1, NONCE);
            System.arraycopy(body, 0, out, 1 + NONCE, body.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The vault could not seal", e);
        }
    }

    static byte[] open(byte[] key, String path, byte[] sealed) throws Broken {
        if (key.length != KEY || sealed.length < 1 + NONCE + TAG_BITS / 8 || sealed[0] != VERSION) throw new Broken();
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(sealed, 1, 1 + NONCE)));
            c.updateAAD(aad(path));
            return c.doFinal(sealed, 1 + NONCE, sealed.length - 1 - NONCE);
        } catch (GeneralSecurityException e) {
            throw new Broken();
        }
    }

    /** HKDF-SHA-256 (RFC 5869) with no salt: extract, then expand to one 32-byte block. */
    static byte[] hkdf(byte[] secret, String info) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[KEY], "HmacSHA256"));
            byte[] prk = mac.doFinal(secret);
            try {
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(info.getBytes(StandardCharsets.UTF_8));
                mac.update((byte) 1);
                return mac.doFinal();
            } finally {
                Arrays.fill(prk, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The vault could not derive a key", e);
        }
    }

    /** 64 MiB, 3 iterations, parallelism 1: heavy for a guesser, about a second for the person. */
    static byte[] argon2id(char[] password, byte[] salt) {
        if (salt.length != SALT) throw new IllegalArgumentException("The vault's salt must be 16 bytes");
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(ARGON_KIB).withIterations(ARGON_ITERATIONS)
            .withParallelism(1).withSalt(salt).build();
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[encoded.remaining()]; // the buffer's array can be longer than what it holds
        encoded.get(bytes);
        Arrays.fill(encoded.array(), (byte) 0); // best effort: the JVM may have copied them already
        try {
            return argon2id(bytes, params);
        } finally {
            Arrays.fill(bytes, (byte) 0); // best effort, as above
        }
    }

    /** The same function with other parameters, so a test can check it against the RFC's own vector. */
    static byte[] argon2id(byte[] password, Argon2Parameters params) {
        Argon2BytesGenerator g = new Argon2BytesGenerator();
        g.init(params);
        byte[] out = new byte[KEY];
        g.generateBytes(password, out);
        return out;
    }

    private static byte[] aad(String path) {
        return (AAD_PREFIX + path).getBytes(StandardCharsets.UTF_8);
    }
}

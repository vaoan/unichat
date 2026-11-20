package app.unichat.cloud;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.Test;

class VaultCryptoTest {
    private static final byte[] PLAIN = "a secret line".getBytes(StandardCharsets.UTF_8);

    @Test
    void sealedOpensWithItsKeyAndPathOnly() throws Exception {
        byte[] key = VaultCrypto.randomKey();
        assertEquals(32, key.length);
        byte[] sealed = VaultCrypto.seal(key, "texts/a.jsonl", PLAIN);
        assertEquals(1, sealed[0]);
        assertArrayEquals(PLAIN, VaultCrypto.open(key, "texts/a.jsonl", sealed));
        assertThrows(VaultCrypto.Broken.class, () -> VaultCrypto.open(VaultCrypto.randomKey(), "texts/a.jsonl", sealed));
        assertThrows(VaultCrypto.Broken.class, () -> VaultCrypto.open(key, "texts/b.jsonl", sealed));
        byte[] flipped = sealed.clone();
        flipped[flipped.length - 1] ^= 1;
        assertThrows(VaultCrypto.Broken.class, () -> VaultCrypto.open(key, "texts/a.jsonl", flipped));
        byte[] otherVersion = sealed.clone();
        otherVersion[0] = 2;
        assertThrows(VaultCrypto.Broken.class, () -> VaultCrypto.open(key, "texts/a.jsonl", otherVersion));
        assertThrows(VaultCrypto.Broken.class, () -> VaultCrypto.open(key, "texts/a.jsonl", new byte[5]));
    }

    @Test
    void everySealIsDifferent() throws Exception {
        byte[] key = VaultCrypto.randomKey();
        byte[] one = VaultCrypto.seal(key, "p", PLAIN), two = VaultCrypto.seal(key, "p", PLAIN);
        assertFalse(Arrays.equals(one, two));
        assertArrayEquals(PLAIN, VaultCrypto.open(key, "p", one));
        assertArrayEquals(PLAIN, VaultCrypto.open(key, "p", two));
    }

    @Test
    void hkdfAndArgonAreStable() {
        byte[] secret = {1, 2, 3, 4};
        assertEquals(32, VaultCrypto.hkdf(secret, "x").length);
        assertArrayEquals(VaultCrypto.hkdf(secret, "x"), VaultCrypto.hkdf(secret, "x"));
        assertFalse(Arrays.equals(VaultCrypto.hkdf(secret, "x"), VaultCrypto.hkdf(secret, "y")));
        byte[] salt = new byte[16], other = new byte[16];
        other[0] = 1;
        char[] password = "correct horse battery".toCharArray();
        byte[] key = VaultCrypto.argon2id(password, salt);
        assertEquals(32, key.length);
        assertArrayEquals(key, VaultCrypto.argon2id(password, salt));
        assertFalse(Arrays.equals(key, VaultCrypto.argon2id(password, other)));
    }

    /** RFC 9106 section 5.3 (Argon2id): proves the Argon2 path computes the standard function, with the RFC's own parameters. */
    @Test
    void argon2PathMatchesRfc9106Vector() {
        Argon2Parameters rfc = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(32).withIterations(3).withParallelism(4)
            .withSalt(filled(16, 2)).withSecret(filled(8, 3)).withAdditional(filled(12, 4)).build();
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
            HexFormat.of().formatHex(VaultCrypto.argon2id(filled(32, 1), rfc)));
    }

    /** Pinned from BC itself at 64 MiB, 3 iterations, parallelism 1: a changed parameter or version changes it. */
    @Test
    void argonTakesTheConstraintsParameters() {
        byte[] salt = filled(16, 7);
        assertEquals("c5a11ba75128bd1b2edc3f948cae6138eb04f2e3f066dd8ade3ed131c7ab04f2", HexFormat.of().formatHex(VaultCrypto.argon2id("correct horse battery".toCharArray(), salt)));
    }

    private static byte[] filled(int n, int v) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) v);
        return b;
    }
}

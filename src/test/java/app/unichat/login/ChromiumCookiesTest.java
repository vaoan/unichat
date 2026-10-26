package app.unichat.login;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ChromiumCookiesTest {
    private static final byte[] KEY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private static byte[] encryptV10(byte[] plain) throws Exception {
        byte[] iv = new byte[16];
        java.util.Arrays.fill(iv, (byte) 0x20);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[3 + ct.length];
        out[0] = 'v';
        out[1] = '1';
        out[2] = '0';
        System.arraycopy(ct, 0, out, 3, ct.length);
        return out;
    }

    @Test
    void classifyMarksWindowsAndV20AsInApp() {
        byte[] v10 = "v10abc".getBytes(StandardCharsets.US_ASCII);
        byte[] v20 = "v20abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals(ChromiumCookies.Support.NEEDS_IN_APP_LOGIN, ChromiumCookies.classify("Windows 11", v10));
        assertEquals(ChromiumCookies.Support.NEEDS_IN_APP_LOGIN, ChromiumCookies.classify("Mac OS X", v20));
        assertEquals(ChromiumCookies.Support.SUPPORTED, ChromiumCookies.classify("Mac OS X", v10));
    }

    @Test
    void decryptsV10WithGivenKey() throws Exception {
        byte[] plain = "synthetic-session-value".getBytes(StandardCharsets.UTF_8);
        assertEquals("synthetic-session-value", ChromiumCookies.decryptValue(encryptV10(plain), KEY));

        // Recent Chrome prepends SHA-256(host_key) to the plaintext.
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(".example.com".getBytes(StandardCharsets.UTF_8));
        byte[] withHash = new byte[hash.length + plain.length];
        System.arraycopy(hash, 0, withHash, 0, hash.length);
        System.arraycopy(plain, 0, withHash, hash.length, plain.length);
        assertEquals("synthetic-session-value", ChromiumCookies.decryptValue(encryptV10(withHash), KEY));
    }
}

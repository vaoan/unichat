package app.unichat.cloud;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/**
 * The code the person prints: 160 random bits written as 32 Crockford base32 characters in 8 groups of 4. Typing it
 * back forgives what the eye mixes up (case, spaces, dashes, O for 0, I or L for 1). It never reaches a log: its text
 * form says nothing of its content.
 */
record RecoveryCode(byte[] bits) {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int BYTES = 20, CHARS = 32, GROUP = 4;
    private static final String DASHES = "-\u2010\u2011\u2012\u2013\u2014\u2015\u2212";
    private static final SecureRandom RANDOM = new SecureRandom();

    RecoveryCode {
        if (bits == null || bits.length != BYTES) throw new IllegalArgumentException("A recovery code is 160 bits");
    }

    static RecoveryCode random() {
        byte[] bits = new byte[BYTES];
        RANDOM.nextBytes(bits);
        return new RecoveryCode(bits);
    }

    /** The code as printed: ABCD-EFGH-… */
    String shown() {
        String plain = plain();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < CHARS; i += GROUP) {
            if (i > 0) out.append('-');
            out.append(plain, i, i + GROUP);
        }
        return out.toString();
    }

    /** One group, counted from 1, to ask for "groups 3 and 7" back. */
    String group(int i) {
        if (i < 1 || i > CHARS / GROUP) throw new IllegalArgumentException("A code has 8 groups");
        return plain().substring((i - 1) * GROUP, i * GROUP);
    }

    /** What was typed, or null when it isn't exactly 160 bits of this alphabet. */
    static RecoveryCode parse(String typed) {
        if (typed == null) return null;
        StringBuilder chars = new StringBuilder();
        for (char c : typed.toUpperCase(Locale.ROOT).toCharArray()) {
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || DASHES.indexOf(c) >= 0 || c == 'U') continue;
            chars.append(c == 'O' ? '0' : c == 'I' || c == 'L' ? '1' : c);
        }
        if (chars.length() != CHARS) return null;
        byte[] bits = new byte[BYTES];
        int acc = 0, have = 0, at = 0;
        for (int i = 0; i < CHARS; i++) {
            int v = ALPHABET.indexOf(chars.charAt(i));
            if (v < 0) return null;
            acc = (acc << 5) | v;
            have += 5;
            if (have >= 8) {
                have -= 8;
                bits[at++] = (byte) (acc >> have);
                acc &= (1 << have) - 1;
            }
        }
        return new RecoveryCode(bits);
    }

    private String plain() {
        StringBuilder out = new StringBuilder();
        int acc = 0, have = 0;
        for (byte b : bits) {
            acc = (acc << 8) | (b & 0xff);
            have += 8;
            while (have >= 5) {
                have -= 5;
                out.append(ALPHABET.charAt((acc >> have) & 31));
            }
            acc &= (1 << have) - 1;
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RecoveryCode c && Arrays.equals(bits, c.bits);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bits);
    }

    @Override
    public String toString() {
        return "RecoveryCode[…]";
    }
}

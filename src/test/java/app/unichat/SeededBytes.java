package app.unichat;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Random;

/** Bytes for a test's files that look like a real file's (no pattern to compress), the same on every run: seeded. */
public final class SeededBytes {
    private SeededBytes() {}

    /** {@code size} bytes from {@code seed}: the same seed, the same bytes. */
    @SuppressFBWarnings(value = {"PREDICTABLE_RANDOM", "DMI_RANDOM_USED_ONLY_ONCE"}, justification = "seeded test data, the same on every run on purpose; nothing secret")
    public static byte[] noise(int size, long seed) {
        byte[] out = new byte[size];
        Random random = new Random(seed);
        random.nextBytes(out);
        return out;
    }
}

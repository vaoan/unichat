package app.unichat.support;

import java.util.Locale;

/** Which operating system this runs on. */
public final class Os {
    private Os() {}

    public static boolean mac() { return name().contains("mac"); }

    public static boolean windows() { return name().contains("win"); }

    /**
     * The download for this computer among those a tool has: Apple-chip Macs, Intel Macs, 64-bit Windows (null where
     * there's none); null for any other computer.
     */
    public static <T> T pick(T appleChip, T intelMac, T windows64) {
        String arch = System.getProperty("os.arch", "");
        boolean arm = arch.equals("aarch64") || arch.equals("arm64"), x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (mac()) return arm ? appleChip : x64 ? intelMac : null;
        return windows() && x64 ? windows64 : null;
    }

    private static String name() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT); }
}

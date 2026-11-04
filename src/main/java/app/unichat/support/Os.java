package app.unichat.support;

import java.util.Locale;

/** Which operating system this runs on. */
public final class Os {
    private Os() {}

    public static boolean mac() { return name().contains("mac"); }

    public static boolean windows() { return name().contains("win"); }

    private static String name() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT); }
}

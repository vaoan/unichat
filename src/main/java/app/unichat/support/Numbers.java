package app.unichat.support;

import org.jspecify.annotations.Nullable;

/**
 * A number read from text that may not be one (a server's answer, a file's header, a property): null when it isn't,
 * instead of a NumberFormatException that every caller would have to remember to catch.
 */
public final class Numbers {
    private Numbers() {}

    /** {@code s} as an int in base 10, or null when it isn't one (or doesn't fit). */
    public static @Nullable Integer intOf(String s) {
        return intOf(s, 10);
    }

    /** {@code s} as an int in {@code radix}, or null when it isn't one (or doesn't fit). */
    public static @Nullable Integer intOf(String s, int radix) {
        try {
            return Integer.parseInt(s, radix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code s} as a long in base 10, or null when it isn't one (or doesn't fit). */
    public static @Nullable Long longOf(String s) {
        return longOf(s, 10);
    }

    /** {@code s} as a long in {@code radix}, or null when it isn't one (or doesn't fit). */
    public static @Nullable Long longOf(String s, int radix) {
        try {
            return Long.parseLong(s, radix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code s} as a double, or null when it isn't one. */
    public static @Nullable Double doubleOf(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

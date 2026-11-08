package app.unichat.support;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Unichat's own version, from the pom ({@code version.properties}, filtered by Maven), and how two versions compare.
 * A release tag may be "v1.2.0"; "1.2.0-beta.1" comes before "1.2.0".
 */
public final class AppVersion {
    /** A run without Maven's resources (an IDE that skipped them): never offered an update, since it's no release. */
    static final String UNKNOWN = "dev";
    private static volatile String current;

    private AppVersion() {}

    /** "1.0.0", or {@value #UNKNOWN} when the build didn't fill it in. */
    public static String current() {
        String v = current;
        if (v == null) current = v = read();
        return v;
    }

    private static String read() {
        try (InputStream in = AppVersion.class.getResourceAsStream("/app/unichat/version.properties")) {
            if (in == null) return UNKNOWN;
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "").strip();
            return v.isEmpty() || v.startsWith("${") ? UNKNOWN : v;
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    /** True when {@code candidate} is a later version than {@code current}. Anything unreadable on either side: false. */
    public static boolean isNewer(String candidate, String current) {
        Parsed a = parse(candidate), b = parse(current);
        return a != null && b != null && compare(a, b) > 0;
    }

    /** True when both name the same version ("v1.2" and "1.2.0"). */
    public static boolean same(String a, String b) {
        Parsed x = parse(a), y = parse(b);
        return x != null && y != null && compare(x, y) == 0;
    }

    /** The numbers before the first '-' and what follows it (empty for a release); build metadata after '+' is ignored. */
    private record Parsed(List<Integer> numbers, String pre) {}

    private static Parsed parse(String v) {
        if (v == null) return null;
        String s = v.strip();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        int dash = s.indexOf('-');
        String core = dash < 0 ? s : s.substring(0, dash);
        String pre = dash < 0 ? "" : s.substring(dash + 1);
        if (core.isEmpty()) return null;
        List<Integer> numbers = new ArrayList<>();
        for (String part : core.split("\\.", -1)) {
            if (part.isEmpty() || !part.chars().allMatch(Character::isDigit) || part.length() > 9) return null;
            numbers.add(Integer.parseInt(part));
        }
        return new Parsed(numbers, pre);
    }

    private static int compare(Parsed a, Parsed b) {
        int n = Math.max(a.numbers().size(), b.numbers().size());
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(at(a.numbers(), i), at(b.numbers(), i));
            if (c != 0) return c;
        }
        if (a.pre().isEmpty() || b.pre().isEmpty()) return Boolean.compare(a.pre().isEmpty(), b.pre().isEmpty());
        return comparePre(a.pre(), b.pre());
    }

    private static int at(List<Integer> l, int i) { return i < l.size() ? l.get(i) : 0; }

    /** As semver: dot by dot, numbers by value and below words; a longer list wins a tie ("beta.1" &gt; "beta"). */
    private static int comparePre(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean xn = x[i].matches("\\d{1,9}"), yn = y[i].matches("\\d{1,9}");
            int c = xn && yn ? Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]))
                    : xn != yn ? (xn ? -1 : 1) : x[i].compareTo(y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    }
}

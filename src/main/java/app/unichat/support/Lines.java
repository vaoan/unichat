package app.unichat.support;

/** Text strangers wrote, as one line of one's own: a name in a forward's note or in a chat's copy of its texts. */
public final class Lines {
    private Lines() {}

    /**
     * {@code s} on one line: line ends, controls and the marks that turn text around become a space. Joiners stay, or
     * an emoji made of several (a family, a flag) would fall apart.
     */
    public static String one(String s) {
        return s.replaceAll("[\\p{Cc}\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069]+", " ").strip();
    }
}

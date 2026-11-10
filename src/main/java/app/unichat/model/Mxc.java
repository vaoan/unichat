package app.unichat.model;

/**
 * A Matrix media address, {@code mxc://<server>/<media id>}. Both parts come from messages, so they're checked before they
 * become part of a request: neither may be empty, nor hold a slash, a space or a control character.
 */
public record Mxc(String server, String mediaId) {
    public static final String SCHEME = "mxc://";

    public Mxc {
        if (!valid(server) || !valid(mediaId)) throw new IllegalArgumentException("Not a media address: " + server + "/" + mediaId);
    }

    /** The address in {@code url}, or null when it isn't a well-formed {@code mxc://} one. */
    public static Mxc parse(String url) {
        if (url == null || !url.startsWith(SCHEME)) return null;
        String rest = url.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash < 0) return null;
        String server = rest.substring(0, slash), mediaId = rest.substring(slash + 1);
        return valid(server) && valid(mediaId) ? new Mxc(server, mediaId) : null;
    }

    private static boolean valid(String part) {
        return part != null && !part.isEmpty() && part.chars().noneMatch(c -> c == '/' || c <= ' ' || c == 0x7f);
    }

    @Override
    public String toString() {
        return SCHEME + server + "/" + mediaId;
    }
}

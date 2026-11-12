package app.unichat.support;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Building web addresses, and finding them in text. */
public final class Urls {
    private Urls() {}

    /** One path segment or query value: URLEncoder writes spaces as "+", which in a path is a literal plus. */
    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * A web address written out in text: http(s), up to a space or quote, without the punctuation that ends a sentence,
     * and ending where a Markdown link's text does ("[https://a/](https://a/?x)" is two addresses, not one of both).
     */
    public static final String ADDRESS = "https?://(?:(?!\\]\\()[^\\s<>\"])+[^\\s<>\".,;:!?)\\]']";

    /** Whether {@code a} and {@code b} are the same page: host and path, whatever the query, fragment or a last slash. */
    public static boolean samePage(String a, String b) {
        try {
            java.net.URI x = java.net.URI.create(a), y = java.net.URI.create(b);
            return x.getHost() != null && x.getHost().equalsIgnoreCase(y.getHost()) && path(x).equals(path(y));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String path(java.net.URI u) {
        String p = u.getPath() == null ? "" : u.getPath();
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }
    private static final java.util.regex.Pattern ADDRESSES = java.util.regex.Pattern.compile(ADDRESS);

    /** The web addresses in {@code text}, in order; none for null. */
    public static java.util.List<String> in(String text) {
        if (text == null) return java.util.List.of();
        return ADDRESSES.matcher(text).results().map(java.util.regex.MatchResult::group).toList();
    }
}

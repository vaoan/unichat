package app.unichat.support;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Building web addresses. */
public final class Urls {
    private Urls() {}

    /** One path segment or query value: URLEncoder writes spaces as "+", which in a path is a literal plus. */
    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

package app.unichat.login;

import app.unichat.model.Network;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bridges a "cookies" login step to Unichat's cookie sources (browser session, in-app window, cookies.txt): the
 * bridge lists the fields it needs and where each comes from; this turns that into a {@link CookieSpec} and the
 * found cookies back into the bridge's field ids. Pure logic, tested.
 */
public final class BridgeCookies {
    private BridgeCookies() {}

    /**
     * True when every required field can come from a cookie. Other sources (request headers, local storage,
     * request bodies) need a browser-level capture Unichat doesn't have; those logins use the bot chat instead.
     */
    public static boolean canCollect(BridgeLogin.CookieParams p) {
        if (p == null || p.fields().isEmpty()) return false;
        for (BridgeLogin.CookieField f : p.fields()) {
            if (f.required() && cookieSource(f) == null) return false;
        }
        return true;
    }

    /**
     * True when it takes the login window, watching the page's own requests: some field wants a request header (LinkedIn
     * wants Cookie, X-LI-Track and X-LI-Page-Instance), which no browser profile or cookies.txt holds.
     */
    public static boolean needsWindowCapture(BridgeLogin.CookieParams p) {
        if (p == null || p.fields().isEmpty() || canCollect(p)) return false;
        for (BridgeLogin.CookieField f : p.fields()) {
            if (f.required() && cookieSource(f) == null && headerSource(f) == null) return false;
        }
        return true;
    }

    /** The request headers to watch for, lower case; the Cookie header is built from the cookies instead. */
    public static Set<String> headersToWatch(BridgeLogin.CookieParams p) {
        Set<String> out = new LinkedHashSet<>();
        for (BridgeLogin.CookieField f : p.fields()) {
            BridgeLogin.Source s = headerSource(f);
            if (s != null && !isCookieHeader(s)) out.add(s.name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** The domain whose cookies make up the Cookie header. */
    public static String cookieDomain(BridgeLogin.CookieParams p) {
        for (BridgeLogin.CookieField f : p.fields()) {
            BridgeLogin.Source s = headerSource(f);
            if (s != null && isCookieHeader(s) && s.cookieDomain() != null) return stripDots(s.cookieDomain());
        }
        String h = host(p.url());
        return h == null ? "" : stripDots(h);
    }

    /** Whether a cookie set for {@code cookieHost} goes to {@code domain} (also its subdomains). */
    public static boolean belongs(String cookieHost, String domain) {
        String h = stripDots(cookieHost == null ? "" : cookieHost);
        return h.equals(domain) || h.endsWith("." + domain);
    }

    /**
     * The step's values from what the window saw, or null while the login isn't finished: the page must have reached
     * where the bridge says it ends, and every required field must have a value of the shape it asks for.
     *
     * @param headers what the page sent, by lower-case name
     */
    public static Map<String, String> captured(BridgeLogin.CookieParams p, String location, Map<String, String> cookies, Map<String, String> headers) {
        if (p.waitForUrlPattern() != null && (location == null || !Pattern.compile(p.waitForUrlPattern()).matcher(location).find())) return null;
        Map<String, String> out = new HashMap<>();
        for (BridgeLogin.CookieField f : p.fields()) {
            String v = value(f, cookies, headers);
            boolean ok = v != null && !v.isBlank() && (f.pattern() == null || Pattern.compile(f.pattern()).matcher(v).find());
            if (ok) out.put(f.id(), v);
            else if (f.required()) return null;
        }
        return out;
    }

    private static String value(BridgeLogin.CookieField f, Map<String, String> cookies, Map<String, String> headers) {
        BridgeLogin.Source h = headerSource(f);
        if (h != null) return isCookieHeader(h) ? cookieHeader(cookies) : headers.get(h.name().toLowerCase(Locale.ROOT));
        BridgeLogin.Source c = cookieSource(f);
        return c == null ? null : cookies.get(c.name());
    }

    private static String cookieHeader(Map<String, String> cookies) {
        return cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; "));
    }

    private static boolean isCookieHeader(BridgeLogin.Source s) { return "Cookie".equalsIgnoreCase(s.name()); }

    private static BridgeLogin.Source headerSource(BridgeLogin.CookieField f) {
        for (BridgeLogin.Source s : f.sources()) if ("request_header".equals(s.type()) && !s.name().isBlank()) return s;
        return null;
    }

    public static CookieSpec spec(Network n, BridgeLogin.CookieParams p) {
        Set<String> required = new LinkedHashSet<>(), optional = new LinkedHashSet<>(), domains = new LinkedHashSet<>();
        for (BridgeLogin.CookieField f : p.fields()) {
            BridgeLogin.Source s = cookieSource(f);
            if (s == null) continue;
            (f.required() ? required : optional).add(s.name());
            String d = s.cookieDomain() != null ? s.cookieDomain() : host(p.url());
            if (d != null) domains.add(stripDots(d));
        }
        if (domains.isEmpty() && host(p.url()) != null) domains.add(stripDots(host(p.url())));
        return CookieSpec.of(n, required, optional, domains);
    }

    /** The cookies found, keyed by the bridge's field ids (what the step submission expects). */
    public static Map<String, String> values(BridgeLogin.CookieParams p, SessionCookies found) {
        Map<String, String> out = new HashMap<>();
        for (BridgeLogin.CookieField f : p.fields()) {
            BridgeLogin.Source s = cookieSource(f);
            if (s == null) continue;
            String v = found.values().get(s.name());
            if (v != null && !v.isBlank()) out.put(f.id(), v);
        }
        return out;
    }

    private static BridgeLogin.Source cookieSource(BridgeLogin.CookieField f) {
        for (BridgeLogin.Source s : f.sources()) if ("cookie".equals(s.type()) && !s.name().isBlank()) return s;
        return null;
    }

    private static String host(String url) {
        try {
            return url == null ? null : URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "www.instagram.com" and ".instagram.com" both mean instagram.com for cookie matching. */
    private static String stripDots(String d) {
        String s = d.toLowerCase(Locale.ROOT);
        while (s.startsWith(".")) s = s.substring(1);
        return s.startsWith("www.") ? s.substring(4) : s;
    }
}

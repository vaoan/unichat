package app.unichat.login;

import app.unichat.model.Network;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

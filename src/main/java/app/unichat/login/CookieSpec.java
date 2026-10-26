package app.unichat.login;

import app.unichat.model.Network;

import java.util.*;

/** Which cookies a "cookie network" needs, and on which domains they live. */
public final class CookieSpec {
    private static final Map<Network, CookieSpec> SPECS = new EnumMap<>(Network.class);

    static {
        SPECS.put(Network.INSTAGRAM, new CookieSpec(
                Set.of("sessionid", "csrftoken"), Set.of("ds_user_id", "mid", "ig_did"),
                Set.of("instagram.com")));
        SPECS.put(Network.MESSENGER, new CookieSpec(
                Set.of("c_user", "xs"), Set.of("datr", "fr"),
                Set.of("facebook.com", "messenger.com")));
        SPECS.put(Network.X, new CookieSpec(
                Set.of("auth_token", "ct0"), Set.of(),
                Set.of("x.com", "twitter.com")));
        SPECS.put(Network.LINKEDIN, new CookieSpec(
                Set.of("li_at", "JSESSIONID"), Set.of(),
                Set.of("linkedin.com")));
        SPECS.put(Network.GOOGLE_MESSAGES, new CookieSpec(
                Set.of("SAPISID", "SID", "__Secure-1PSID"),
                Set.of("HSID", "SSID", "APISID", "__Secure-3PSID"),
                Set.of("google.com")));
    }

    private final Set<String> required;
    private final Set<String> optional;
    private final Set<String> domains;

    private CookieSpec(Set<String> required, Set<String> optional, Set<String> domains) {
        this.required = required;
        this.optional = optional;
        this.domains = domains;
    }

    /** @throws IllegalArgumentException if the network does not log in with cookies */
    public static CookieSpec forNetwork(Network network) {
        CookieSpec s = SPECS.get(network);
        if (s == null) throw new IllegalArgumentException("Not a cookie network: " + network);
        return s;
    }

    public Set<String> required() { return required; }
    public Set<String> optional() { return optional; }
    public Set<String> domains() { return domains; }

    /** True if a cookie domain (e.g. {@code .facebook.com}) belongs to this network. */
    public boolean isForNetwork(String cookieDomain) {
        if (cookieDomain == null) return false;
        String d = cookieDomain.toLowerCase(Locale.ROOT);
        while (d.startsWith(".")) d = d.substring(1);
        for (String base : domains) {
            if (d.equals(base) || d.endsWith("." + base)) return true;
        }
        return false;
    }

    /** Keeps only the required and optional cookies from a name to value map. */
    public SessionCookies select(Map<String, String> allCookiesByName) {
        Map<String, String> out = new HashMap<>();
        for (String n : required) put(out, allCookiesByName, n);
        for (String n : optional) put(out, allCookiesByName, n);
        return new SessionCookies(networkOf(), out);
    }

    /** Names of required cookies absent (or blank) in the given map, sorted. */
    public List<String> missing(Map<String, String> allCookiesByName) {
        return required.stream()
                .filter(n -> allCookiesByName.get(n) == null || allCookiesByName.get(n).isBlank())
                .sorted().toList();
    }

    private static void put(Map<String, String> out, Map<String, String> in, String name) {
        String v = in.get(name);
        if (v != null) out.put(name, v);
    }

    private Network networkOf() {
        for (var e : SPECS.entrySet()) if (e.getValue() == this) return e.getKey();
        throw new IllegalStateException();
    }
}

package app.unichat.login;

import app.unichat.support.I18n;
import app.unichat.support.Threads;
import app.unichat.model.Network;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Entry points for the cookie-based login sources (browser sessions, cookies.txt). */
public final class CookieLogin {
    private static final ExecutorService IO = Executors.newCachedThreadPool(Threads.daemon("cookie-login-io"));

    private CookieLogin() {}

    /** True for networks that log in with browser cookies. */
    public static boolean isCookieNetwork(Network network) {
        try {
            CookieSpec.forNetwork(network);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Looks for existing sessions in Firefox and Chromium-family browsers on a daemon thread
     * (the macOS keychain prompt can block). Never fails: errors yield an empty contribution.
     */
    public static CompletableFuture<List<BrowserSession>> findBrowserSessions(Network network) {
        if (!isCookieNetwork(network)) return CompletableFuture.completedFuture(new ArrayList<>());
        return findBrowserSessions(CookieSpec.forNetwork(network));
    }

    /** Same, for the cookies a bridge asks for. */
    public static CompletableFuture<List<BrowserSession>> findBrowserSessions(CookieSpec spec) {
        return CompletableFuture.supplyAsync(() -> {
            List<BrowserSession> all = new ArrayList<>();
            try {
                all.addAll(FirefoxCookies.findSessions(spec));
            } catch (RuntimeException | LinkageError e) {
                // ignore: this source is simply unavailable
            }
            try {
                all.addAll(ChromiumCookies.findSessions(spec));
            } catch (RuntimeException | LinkageError e) {
                // ignore: this source is simply unavailable
            }
            return all;
        }, IO).exceptionally(e -> List.of());
    }

    /** Outcome of importing a cookies.txt file. */
    public record TxtResult(Kind kind, SessionCookies cookies, List<String> missing, String message) {
        public enum Kind { OK, MISSING, ERROR }

        public TxtResult {
            missing = List.copyOf(missing);
        }

        public static TxtResult ok(SessionCookies cookies) {
            return new TxtResult(Kind.OK, cookies, List.of(), null);
        }

        public static TxtResult missing(List<String> names) {
            return new TxtResult(Kind.MISSING, null, names, null);
        }

        public static TxtResult error(String message) {
            return new TxtResult(Kind.ERROR, null, List.of(), message);
        }
    }

    /** Reads {@code file} as cookies.txt for {@code network}. Never throws. */
    public static TxtResult fromCookieTxt(Path file, Network network) {
        if (!isCookieNetwork(network)) {
            return TxtResult.error(I18n.t("login.cookies.notCookieNetwork"));
        }
        return fromCookieTxt(file, CookieSpec.forNetwork(network));
    }

    /** Same, for the cookies a bridge asks for. */
    public static TxtResult fromCookieTxt(Path file, CookieSpec spec) {
        SessionCookies cookies;
        try {
            cookies = CookieTxt.read(file, spec, Instant.now());
        } catch (IOException | RuntimeException e) {
            return TxtResult.error(I18n.t("login.cookies.unreadable"));
        }
        List<String> missing = spec.required().stream()
                .filter(n -> {
                    String v = cookies.values().get(n);
                    return v == null || v.isBlank();
                })
                .sorted()
                .toList();
        return missing.isEmpty() ? TxtResult.ok(cookies) : TxtResult.missing(missing);
    }
}

package app.unichat.cloud;

import app.unichat.support.I18n;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The passkey probe's page: whether this computer's browser and password manager can make a passkey for Unichat and
 * give back the PRF value the vault would be opened with. Served on {@link Loopback} at {@code localhost} (WebAuthn's
 * relying party: a page at 127.0.0.1 is refused), with no script or style from anywhere else; the browser posts each
 * step's result back with {@code state}, never the PRF bytes, only how many came.
 */
public final class PasskeyPage {
    /** What the page did: made a passkey, or used one already made. */
    public enum Step { CREATE, GET }

    /**
     * One step's result as the browser told it. {@code prfEnabled}: what the browser said at creation, null when it said
     * nothing; {@code prfLength}: how many PRF bytes came back, -1 none. Every text one line, cut short: the record is
     * the browser's to write into.
     */
    public record Finding(Step step, Boolean prfEnabled, int prfLength, String credentialId, String attachment,
                          String userAgent, String error) {
        public boolean prf() {
            return prfLength > 0;
        }

        /** What it means, in the person's words: the page shows it, and so does Ajustes. */
        public String said() {
            if (!error.isEmpty()) return I18n.t("cloud.passkey.failed", error);
            if (step == Step.GET) return I18n.t(prf() ? "cloud.passkey.gotPrf" : "cloud.passkey.noPrf");
            if (prf() || Boolean.TRUE.equals(prfEnabled)) return I18n.t("cloud.passkey.createdPrf");
            return I18n.t(prfEnabled == null ? "cloud.passkey.createdUnknown" : "cloud.passkey.createdNoPrf");
        }

        /** For the record the person sends back: no secret in it (the passkey's id is public). */
        public String line() {
            return "step=" + step.name().toLowerCase(java.util.Locale.ROOT)
                    + " prfEnabled=" + (prfEnabled == null ? "unknown" : prfEnabled)
                    + " prfLength=" + (prfLength < 0 ? "none" : prfLength)
                    + " credentialId=" + credentialId + " attachment=" + (attachment.isEmpty() ? "unknown" : attachment)
                    + " error=\"" + error + "\" userAgent=\"" + userAgent + "\"";
        }
    }

    private static final int MOST = 300;
    private static final int BODY_LIMIT = 16 * 1024;

    private final HttpServer server;
    private final String state = Loopback.random(16);
    private final Consumer<Finding> found;
    private final CompletableFuture<Void> session = new CompletableFuture<>();

    private PasskeyPage(Consumer<Finding> found, int port, Duration deadline) throws IOException {
        this.found = found;
        server = Loopback.server(port);
        server.createContext("/passkey/result", this::result);
        server.createContext("/passkey", this::page);
        server.start();
        Loopback.until(server, session, deadline);
    }

    /**
     * Serves the page and has {@code browser} open it; each step the browser reports goes to {@code found}. Ends at
     * {@link Loopback#DEADLINE} or with {@link CompletableFuture#cancel}, the port free again.
     */
    public static CompletableFuture<Void> start(Consumer<URI> browser, Consumer<Finding> found) throws IOException {
        return start(browser, found, Loopback.PORT, Loopback.DEADLINE);
    }

    static CompletableFuture<Void> start(Consumer<URI> browser, Consumer<Finding> found, int port, Duration deadline) throws IOException {
        PasskeyPage page = new PasskeyPage(found, port, deadline);
        browser.accept(URI.create("http://localhost:" + page.server.getAddress().getPort() + "/passkey"));
        return page.session;
    }

    /** The PRF's input: fixed, so the same passkey gives the same value every time, on any computer. */
    static byte[] salt() {
        try {
            return MessageDigest.getInstance("SHA-256").digest("unichat-vault passkey prf".getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void page(HttpExchange x) throws IOException {
        if (!"GET".equals(x.getRequestMethod()) || !"/passkey".equals(x.getRequestURI().getPath())) {
            Loopback.reply(x, 404, "text/plain; charset=utf-8", "");
            return;
        }
        // Nothing but this page's own script and style, and answers only to itself.
        x.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        Loopback.reply(x, 200, "text/html; charset=utf-8", PasskeyHtml.page(state, Base64.getEncoder().encodeToString(salt()), texts()));
    }

    private void result(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) {
            Loopback.reply(x, 405, "text/plain; charset=utf-8", "");
            return;
        }
        byte[] raw = x.getRequestBody().readNBytes(BODY_LIMIT);
        Map<String, String> q = Loopback.query(new String(raw, StandardCharsets.UTF_8));
        if (!Loopback.ours(state, q.get("state"))) {
            Loopback.reply(x, 400, "text/plain; charset=utf-8", I18n.t("cloud.passkey.notOurs"));
            return; // another page's post: not this probe's
        }
        Step step = switch (q.getOrDefault("step", "")) {
            case "create" -> Step.CREATE;
            case "get" -> Step.GET;
            default -> null;
        };
        if (step == null) {
            Loopback.reply(x, 400, "text/plain; charset=utf-8", "");
            return;
        }
        Finding f = new Finding(step, bool(q.get("prfEnabled")), length(q.get("prfLength")),
                kept(q.get("credentialId"), "[A-Za-z0-9_-]*"), kept(q.get("attachment"), "[a-z-]*"),
                line(q.get("userAgent")), line(q.get("error")));
        found.accept(f);
        Loopback.reply(x, 200, "text/plain; charset=utf-8", f.said());
    }

    private static Boolean bool(String s) {
        return "true".equals(s) ? Boolean.TRUE : "false".equals(s) ? Boolean.FALSE : null;
    }

    private static int length(String s) {
        return s != null && s.matches("\\d{1,4}") ? Integer.parseInt(s) : -1;
    }

    /** {@code s} if it's only what such a value is made of, else "?". */
    private static String kept(String s, String allowed) {
        if (s == null || s.isEmpty()) return "";
        return s.length() <= MOST && s.matches(allowed) ? s : "?";
    }

    /** One line, no quotes to end the record's field early, cut short. */
    private static String line(String s) {
        if (s == null) return "";
        String one = s.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]+", " ").replace('"', '\'').strip();
        return one.length() <= MOST ? one : one.substring(0, MOST);
    }

    /** The page's words, as one JSON object the script reads. */
    private static String texts() {
        Map<String, String> t = new LinkedHashMap<>();
        for (String k : new String[] {"title", "intro", "create", "use", "createHelp", "useHelp", "working", "noWebAuthn",
                "notSent"}) {
            t.put(k, I18n.t("cloud.passkey.page." + k));
        }
        t.put("lang", I18n.locale().getLanguage());
        try {
            // Inside a <script>: "</" would end it.
            return new ObjectMapper().writeValueAsString(t).replace("</", "<\\/");
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}

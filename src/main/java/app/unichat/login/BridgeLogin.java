package app.unichat.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A bridge's own login API (mautrix bridgev2 provisioning, {@code /_matrix/provision/v3/login/...}): the bridge says
 * which login methods it has and what each step needs, so Unichat never hardcodes them. Authenticated with the
 * user's Matrix access token. Blocking calls: use off the FX thread.
 */
public final class BridgeLogin {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A login method offered by the bridge (e.g. WhatsApp "qr" and "phone"). */
    public record Flow(String id, String name, String description) {}

    /** One input of a {@code user_input} step. {@code type}: phone_number, 2fa_code, password, username, email, token, url, domain, select, captcha_code. */
    public record Field(String type, String id, String name, String description, String defaultValue, boolean optional,
                        String pattern, List<String> options) {}

    /** Where a cookie-step value comes from; {@code type} is cookie, local_storage, request_header, request_body or special. */
    public record Source(String type, String name, String cookieDomain) {}

    public record CookieField(String id, boolean required, List<Source> sources) {}

    public record CookieParams(String url, String userAgent, List<CookieField> fields, String extractJs, String waitForUrlPattern) {}

    /** What to show while the bridge waits: {@code type} qr, emoji, code or nothing. */
    public record Display(String type, String data, String imageUrl, boolean canCancel) {}

    /**
     * One step of a login. {@code type}: user_input, cookies, display_and_wait, complete (client_http and webauthn
     * are not supported by Unichat). Exactly the params matching {@code type} are set.
     */
    public record Step(String loginId, String type, String stepId, String instructions, List<Field> fields,
                       Display display, CookieParams cookies, String userLoginId) {
        public boolean isComplete() { return "complete".equals(type); }
    }

    /** The bridge refused a request; {@code errcode} like M_FORBIDDEN, message from the bridge. */
    public static final class BridgeLoginException extends IOException {
        private final String errcode;

        public BridgeLoginException(String errcode, String message) {
            super(message);
            this.errcode = errcode;
        }

        public String errcode() { return errcode; }
    }

    private final String base;
    private final String accessToken;
    private final String userId;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** @param base e.g. {@code http://127.0.0.1:29318/_matrix/provision} */
    public BridgeLogin(String base, String accessToken, String userId) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.accessToken = accessToken;
        this.userId = userId;
    }

    /** An account signed in on this bridge; {@code state} like CONNECTED, CONNECTING, BAD_CREDENTIALS. */
    public record Account(String id, String name, String state) {}

    /** The accounts signed in on this bridge (whoami): the source of truth, no bot chat needed. */
    public List<Account> accounts() throws IOException, InterruptedException {
        List<Account> out = new ArrayList<>();
        for (JsonNode l : call("GET", "/v3/whoami", null, Duration.ofSeconds(30)).path("logins")) {
            JsonNode st = l.path("state");
            String state = text(st, "state_event");
            String name = text(l, "name");
            if (name == null) name = text(l.path("profile"), "name");
            out.add(new Account(l.path("id").asText(), name, state));
        }
        return out;
    }

    public List<Flow> flows() throws IOException, InterruptedException {
        List<Flow> out = new ArrayList<>();
        for (JsonNode f : call("GET", "/v3/login/flows", null, Duration.ofSeconds(30)).path("flows")) {
            out.add(new Flow(f.path("id").asText(), f.path("name").asText(), f.path("description").asText()));
        }
        return out;
    }

    public Step start(String flowId) throws IOException, InterruptedException {
        return step(call("POST", "/v3/login/start/" + enc(flowId), JSON.createObjectNode(), Duration.ofSeconds(60)));
    }

    /** Sends a user_input or cookies step: values keyed by field id. */
    public Step submit(Step step, Map<String, String> values) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        values.forEach(body::put);
        return step(call("POST", "/v3/login/step/" + enc(step.loginId()) + "/" + enc(step.stepId()) + "/" + enc(step.type()),
                body, Duration.ofSeconds(120)));
    }

    /** For display_and_wait: returns when the user did the thing (scanned the QR…) or the step changed (new QR). */
    public Step await(Step step) throws IOException, InterruptedException {
        return step(call("POST", "/v3/login/step/" + enc(step.loginId()) + "/" + enc(step.stepId()) + "/display_and_wait",
                JSON.createObjectNode(), Duration.ofMinutes(10)));
    }

    public void cancel(Step step) {
        try {
            call("POST", "/v3/login/cancel/" + enc(step.loginId()), JSON.createObjectNode(), Duration.ofSeconds(10));
        } catch (IOException e) {
            // Already gone.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- parsing

    static Step step(JsonNode j) {
        String type = j.path("type").asText();
        List<Field> fields = new ArrayList<>();
        for (JsonNode f : j.path("user_input").path("fields")) {
            List<String> options = new ArrayList<>();
            for (JsonNode o : f.path("options")) options.add(o.asText());
            fields.add(new Field(f.path("type").asText(), f.path("id").asText(), f.path("name").asText(),
                    f.path("description").asText(), text(f, "default_value"), f.path("optional").asBoolean(false),
                    text(f, "pattern"), List.copyOf(options)));
        }
        Display display = null;
        JsonNode d = j.path("display_and_wait");
        if (!d.isMissingNode()) {
            display = new Display(d.path("type").asText(), text(d, "data"), text(d, "image_url"), d.path("can_cancel").asBoolean(false));
        }
        CookieParams cookies = null;
        JsonNode c = j.path("cookies");
        if (!c.isMissingNode()) {
            List<CookieField> cf = new ArrayList<>();
            for (JsonNode f : c.path("fields")) {
                List<Source> sources = new ArrayList<>();
                for (JsonNode s : f.path("sources")) {
                    sources.add(new Source(s.path("type").asText(), s.path("name").asText(), text(s, "cookie_domain")));
                }
                cf.add(new CookieField(f.path("id").asText(), f.path("required").asBoolean(false), List.copyOf(sources)));
            }
            cookies = new CookieParams(c.path("url").asText(), text(c, "user_agent"), List.copyOf(cf), text(c, "extract_js"),
                    text(c, "wait_for_url_pattern"));
        }
        return new Step(j.path("login_id").asText(), type, j.path("step_id").asText(), j.path("instructions").asText(""),
                List.copyOf(fields), display, cookies, text(j.path("complete"), "user_login_id"));
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() || v.asText().isEmpty() ? null : v.asText();
    }

    // ---------------------------------------------------------------- http

    private JsonNode call(String method, String path, JsonNode body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path + "?user_id=" + enc(userId)))
                .timeout(timeout)
                .header("Authorization", "Bearer " + accessToken);
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode j = r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
        if (r.statusCode() >= 400) {
            throw new BridgeLoginException(j.path("errcode").asText("M_UNKNOWN"), j.path("error").asText("HTTP " + r.statusCode()));
        }
        return j;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

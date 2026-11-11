package app.unichat.login;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

import static app.unichat.support.Urls.enc;

/** A bridge's provisioning API for one of the person's logins ({@code user_id} and {@code login_id} on every call). */
record LoginApi(String base, String accessToken, String userId, String loginId) {
    /**
     * The answer, or null when the bridge doesn't have this endpoint (404 {@code M_UNRECOGNIZED}: "not here", not an
     * error). {@code query}: more parameters, already encoded, each starting with {@code &}.
     */
    JsonNode call(HttpClient http, String method, String path, String query, JsonNode body, Duration timeout)
            throws IOException, InterruptedException {
        String q = "?user_id=" + enc(userId) + (loginId == null ? "" : "&login_id=" + enc(loginId)) + query;
        Provisioning.Answer a = Provisioning.call(http, method, base + path + q, accessToken, body, timeout);
        if (a.status() == 404 && "M_UNRECOGNIZED".equals(a.json().path("errcode").asText())) return null;
        return a.orThrow();
    }
}

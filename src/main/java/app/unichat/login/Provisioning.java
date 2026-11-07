package app.unichat.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** One call to a bridge's provisioning API. */
final class Provisioning {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Provisioning() {}

    record Answer(int status, JsonNode json) {
        /** The body, or the bridge's own error. */
        JsonNode orThrow() throws BridgeLogin.BridgeLoginException {
            if (status < 400) return json;
            throw new BridgeLogin.BridgeLoginException(json.path("errcode").asText("M_UNKNOWN"), json.path("error").asText("HTTP " + status));
        }
    }

    static Answer call(HttpClient http, String method, String url, String accessToken, JsonNode body, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + accessToken);
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(r.statusCode(), r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body()));
    }
}

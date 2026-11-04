package app.unichat.server;

import app.unichat.bridge.BridgeStates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusEndpointTest {
    private final BridgeStates states = new BridgeStates();
    private final StatusEndpoint endpoint = new StatusEndpoint(states, b -> b.equals("whatsapp") ? "as-secret" : null);

    @AfterEach
    void stop() {
        endpoint.stop();
    }

    private int post(String url, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void aBridgeReportsWithItsOwnToken() throws Exception {
        String url = endpoint.url("whatsapp");
        assertTrue(url.startsWith("http://127.0.0.1:"), url);
        assertEquals(204, post(url, "as-secret", "{\"state_event\":\"LOGGED_OUT\",\"remote_id\":\"57\"}"));
        assertEquals("LOGGED_OUT", states.accounts("whatsapp").get(0).event());
    }

    @Test
    void anythingElseIsTurnedAway() throws Exception {
        String url = endpoint.url("whatsapp");
        assertEquals(401, post(url, "wrong", "{\"state_event\":\"CONNECTED\",\"remote_id\":\"57\"}"));
        assertEquals(401, post(url, null, "{\"state_event\":\"CONNECTED\",\"remote_id\":\"57\"}"));
        assertEquals(401, post(endpoint.url("discord"), "as-secret", "{\"state_event\":\"CONNECTED\",\"remote_id\":\"1\"}"),
                "one bridge's token doesn't speak for another");
        assertEquals(400, post(url, "as-secret", "{not json"));
        assertTrue(states.accounts("whatsapp").isEmpty());
        assertTrue(states.accounts("discord").isEmpty());
    }
}

package app.unichat.login;

import app.unichat.model.Contact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.Optional;

/**
 * A bridge's contacts API (bridgev2 provisioning {@code /v3/contacts|search_users|resolve_identifier|create_dm}) for
 * one login. Bridges that don't implement listing or search answer 404 {@code M_UNRECOGNIZED}: that's "not
 * available here" (empty Optional), not an error. Only the user's typed query is sent.
 */
public final class BridgeContacts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String base;
    private final String accessToken;
    private final String userId;
    private final String loginId;

    /** @param base e.g. {@code http://127.0.0.1:29318/_matrix/provision} */
    public BridgeContacts(String base, String accessToken, String userId, String loginId) {
        this.base = base;
        this.accessToken = accessToken;
        this.userId = userId;
        this.loginId = loginId;
    }

    public Optional<List<Contact>> list() throws IOException, InterruptedException {
        JsonNode j = call("GET", "/v3/contacts", null);
        return j == null ? Optional.empty() : Optional.of(contacts(j.path("contacts")));
    }

    public Optional<List<Contact>> search(String query) throws IOException, InterruptedException {
        JsonNode j = call("POST", "/v3/search_users", JSON.createObjectNode().put("query", query));
        return j == null ? Optional.empty() : Optional.of(contacts(j.path("results")));
    }

    /** A phone number, @username… as the network knows it; null when nobody matches. */
    public Contact resolve(String identifier) throws IOException, InterruptedException {
        try {
            JsonNode j = call("GET", "/v3/resolve_identifier/" + segment(identifier), null);
            return j == null || j.path("id").asText("").isEmpty() ? null : contact(j);
        } catch (BridgeLogin.BridgeLoginException e) {
            if ("M_NOT_FOUND".equals(e.errcode())) return null;
            throw e;
        }
    }

    /** Opens (creating if needed) the DM with {@code contactId}; returns its room id. */
    public String createDm(String contactId) throws IOException, InterruptedException {
        JsonNode j = call("POST", "/v3/create_dm/" + segment(contactId), JSON.createObjectNode());
        if (j == null) throw new BridgeLogin.BridgeLoginException("M_UNRECOGNIZED", "Esta red no permite abrir chats nuevos desde Unichat");
        String room = j.path("dm_room_mxid").asText("");
        if (room.isEmpty()) throw new BridgeLogin.BridgeLoginException("M_UNKNOWN", "El puente no devolvió el chat");
        return room;
    }

    static List<Contact> contacts(JsonNode arr) {
        List<Contact> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(contact(n));
        return out;
    }

    private static Contact contact(JsonNode n) {
        List<String> ids = new ArrayList<>();
        for (JsonNode i : n.path("identifiers")) ids.add(i.asText());
        return new Contact(n.path("id").asText(), n.path("name").asText(""), ids, blank(n.path("mxid").asText("")),
                blank(n.path("dm_room_mxid").asText("")));
    }

    private static String blank(String s) {
        return s.isEmpty() ? null : s;
    }

    /** The response, or null when the bridge doesn't have this endpoint. */
    private JsonNode call(String method, String path, JsonNode body) throws IOException, InterruptedException {
        String q = "?user_id=" + enc(userId) + (loginId == null ? "" : "&login_id=" + enc(loginId));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path + q)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken);
        if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        else b.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode j = BridgeLogin.bodyOf(r);
        if (r.statusCode() == 404 && "M_UNRECOGNIZED".equals(j.path("errcode").asText())) return null;
        if (r.statusCode() >= 400) {
            throw new BridgeLogin.BridgeLoginException(j.path("errcode").asText("M_UNKNOWN"), j.path("error").asText("HTTP " + r.statusCode()));
        }
        return j;
    }

    /** A URL path segment: URLEncoder writes spaces as "+", which in a path is a literal plus. */
    private static String segment(String s) {
        return enc(s).replace("+", "%20");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

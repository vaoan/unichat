package app.unichat.login;

import app.unichat.model.Network;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/** The cookies selected for one network, ready to be handed to the bridge. */
public record SessionCookies(Network network, Map<String, String> values) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public SessionCookies {
        values = Map.copyOf(values);
    }

    /** True when every required cookie of the network is present and non-blank. */
    public boolean complete() {
        return CookieSpec.forNetwork(network).required().stream()
                .allMatch(n -> values.get(n) != null && !values.get(n).isBlank());
    }

    /** Never prints cookie values: they are live session credentials. */
    @Override
    public String toString() {
        return "SessionCookies[network=" + network + ", names=" + new java.util.TreeSet<>(values.keySet()) + "]";
    }

    /** JSON object {@code {"name":"value",...}} as expected by the bridge login. */
    public String toBridgeJson() {
        try {
            return JSON.writeValueAsString(new TreeMap<>(values));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

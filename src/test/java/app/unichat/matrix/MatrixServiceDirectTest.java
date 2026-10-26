package app.unichat.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class MatrixServiceDirectTest {

    private static JsonNode json(String s) {
        try {
            return MatrixClient.JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void keepsServerEntriesAndAddsNewBot() {
        JsonNode server = json("{\"@a:x\":[\"!1\"],\"@b:x\":[\"!2\"]}");
        ObjectNode out = MatrixChatService.mergeDirect(server, json("{}"), "@c:x", "!3");
        assertEquals(json("{\"@a:x\":[\"!1\"],\"@b:x\":[\"!2\"],\"@c:x\":[\"!3\"]}"), out);
    }

    @Test
    void doesNotDuplicateRoom() {
        JsonNode server = json("{\"@a:x\":[\"!1\"]}");
        ObjectNode out = MatrixChatService.mergeDirect(server, json("{\"@a:x\":[\"!1\"]}"), "@a:x", "!1");
        assertEquals(json("{\"@a:x\":[\"!1\"]}"), out);
    }

    @Test
    void nullOrEmptyServerFallsBackToCached() {
        JsonNode cached = json("{\"@a:x\":[\"!1\"]}");
        assertEquals(json("{\"@a:x\":[\"!1\"],\"@c:x\":[\"!3\"]}"),
                MatrixChatService.mergeDirect(null, cached, "@c:x", "!3"));
        assertEquals(json("{\"@a:x\":[\"!1\"],\"@c:x\":[\"!3\"]}"),
                MatrixChatService.mergeDirect(json("{}"), cached, "@c:x", "!3"));
    }

    @Test
    void localOnlyEntrySurvivesAndListsAreUnioned() {
        JsonNode server = json("{\"@a:x\":[\"!1\"]}");
        JsonNode cached = json("{\"@a:x\":[\"!1\",\"!9\"],\"@l:x\":[\"!8\"]}");
        ObjectNode out = MatrixChatService.mergeDirect(server, cached, "@a:x", "!3");
        assertEquals(json("{\"@a:x\":[\"!1\",\"!9\",\"!3\"],\"@l:x\":[\"!8\"]}"), out);
        assertTrue(out.has("@l:x"));
    }
}

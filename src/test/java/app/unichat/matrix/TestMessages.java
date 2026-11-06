package app.unichat.matrix;

import app.unichat.model.Message;
import com.fasterxml.jackson.databind.JsonNode;

/** For tests outside this package: a message filled the way a sync fills it. */
public final class TestMessages {
    private TestMessages() {}

    public static void apply(Message m, String msgtype, String contentJson) throws Exception {
        JsonNode content = MatrixClient.JSON.readTree(contentJson);
        SyncEvents.applyContent(m, msgtype, content.path("body").asText(), content);
    }
}

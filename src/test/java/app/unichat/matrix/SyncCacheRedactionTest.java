package app.unichat.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SyncCacheRedactionTest {

    private static final String SYNC = """
            {"next_batch":"s123","account_data":{"events":[{"type":"x","content":{"a":1}}]},
             "rooms":{"join":{
               "!bot:s":{"unread_notifications":{"notification_count":2},
                 "state":{"events":[
                   {"type":"m.room.member","state_key":"@u:s","content":{"membership":"join"}},
                   {"type":"m.room.name","state_key":"","content":{"name":"Bot DM"}},
                   {"type":"m.room.message","content":{"body":"stateSECRETVALUE"}}]},
                 "timeline":{"events":[
                   {"type":"m.room.message","event_id":"$1","content":{"msgtype":"m.text","body":"login sessionid=SECRETVALUE"}}]}},
               "!ok:s":{"timeline":{"events":[
                   {"type":"m.room.message","event_id":"$2","content":{"msgtype":"m.text","body":"hello normal"}}]}}
             }}}
            """;

    @Test
    void redactsBotRoomButKeepsEverythingElse() throws Exception {
        JsonNode in = MatrixClient.JSON.readTree(SYNC);
        JsonNode out = MatrixChatService.redactForCache(in, Set.of("!bot:s"));
        String s = MatrixClient.JSON.writeValueAsString(out);
        assertFalse(s.contains("SECRETVALUE"));
        assertFalse(s.contains("login sessionid"));
        assertTrue(s.contains("hello normal"));
        assertEquals("s123", out.path("next_batch").asText());
        assertTrue(s.contains("\"Bot DM\""));
        assertEquals("join", out.at("/rooms/join/!bot:s/state/events/0/content/membership").asText());
        assertEquals(2, out.at("/rooms/join/!bot:s/unread_notifications/notification_count").asInt());
        assertTrue(MatrixClient.JSON.writeValueAsString(in).contains("SECRETVALUE"));
    }

    @Test
    void keepsStateEventsInTimelineButBlanksMessages() throws Exception {
        String json = """
                {"next_batch":"s1","rooms":{"join":{"!bot:s":{"timeline":{"events":[
                  {"type":"m.room.member","state_key":"@b:s","content":{"membership":"join","displayname":"Bridge Bot"}},
                  {"type":"m.room.name","state_key":"","content":{"name":"Instagram bridge"}},
                  {"type":"m.room.message","content":{"body":"cookie sessionid=SECRETVALUE"}}]}}}}}
                """;
        JsonNode out = MatrixChatService.redactForCache(MatrixClient.JSON.readTree(json), Set.of("!bot:s"));
        String s = MatrixClient.JSON.writeValueAsString(out);
        assertTrue(s.contains("Bridge Bot"));
        assertTrue(s.contains("Instagram bridge"));
        assertFalse(s.contains("SECRETVALUE"));
    }
}

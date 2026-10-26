package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.model.RoomFeatures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each chat carries what its network supports (com.beeper.room_features), kept up to date. */
class RoomFeaturesSyncTest {
    private static final String ME = "@me:example.org";
    private SyncProcessor proc;

    @BeforeEach
    void setUp() {
        proc = new SyncProcessor(ME);
    }

    private static JsonNode json(String s) {
        try {
            return MatrixClient.JSON.readTree(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void sync(String roomId, String stateEvents) {
        proc.process(json("{\"rooms\":{\"join\":{\"" + roomId + "\":{\"state\":{\"events\":[" + stateEvents + "]}}}}}"), true);
    }

    private static String member(String user) {
        return "{\"type\":\"m.room.member\",\"state_key\":\"" + user + "\",\"sender\":\"" + user + "\",\"content\":{\"membership\":\"join\"}}";
    }

    private static String features(String content) {
        return "{\"type\":\"com.beeper.room_features\",\"state_key\":\"fi.mau.whatsapp\",\"sender\":\"@whatsappbot:example.org\",\"content\":" + content + "}";
    }

    private Chat chat(String id) {
        return proc.rooms().stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow().chat;
    }

    @Test
    void portalCarriesItsBridgesFeaturesAndFollowsChanges() {
        String wa = member(ME) + "," + member("@whatsapp_573001112233:example.org");
        sync("!wa:example.org", wa + "," + features("{\"id\":\"v1\",\"edit\":2,\"edit_max_age\":900}"));
        Instant sent = Instant.parse("2026-10-26T10:00:00Z");
        assertTrue(chat("!wa:example.org").features().canEdit(sent, sent.plusSeconds(60)));
        assertFalse(chat("!wa:example.org").features().canEdit(sent, sent.plusSeconds(20 * 60)));

        sync("!wa:example.org", features("{\"id\":\"v2\",\"edit\":0}"));
        assertFalse(chat("!wa:example.org").features().canEdit(sent, sent.plusSeconds(60)), "updated when the bridge changes it");
    }

    @Test
    void discordRoomsWithoutFeaturesUseTheDiscordTable() {
        sync("!dc:example.org", member(ME) + "," + member("@discord_123456789012345678:example.org"));
        assertEquals(RoomFeatures.discord(), chat("!dc:example.org").features());
    }

    @Test
    void plainMatrixRoomsOfferEverything() {
        sync("!m:example.org", member(ME) + "," + member("@ana:example.org"));
        assertSame(RoomFeatures.UNKNOWN, chat("!m:example.org").features());
    }
}

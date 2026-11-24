package app.unichat.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With what each bridge really publishes. */
class RoomFeaturesVoiceTest {
    private static RoomFeatures parse(String json) throws JsonProcessingException {
        return RoomFeatures.parse(new ObjectMapper().readTree(json));
    }

    /** The way a voice note goes out there, which the test expects to be offered. */
    private static RoomFeatures.VoiceSend offered(RoomFeatures f) {
        RoomFeatures.VoiceSend v = f.voiceSend();
        assertNotNull(v, "a voice note is offered");
        return v;
    }

    private static String voice(String mimes) {
        return "{\"file\":{\"org.matrix.msc3245.voice\":{\"mime_types\":" + mimes + "},\"m.audio\":{\"mime_types\":{\"audio/mp4\":2,\"audio/mpeg\":2}}}}";
    }

    @Test
    void whatsAppWantsTheCodecNamed() throws Exception {
        RoomFeatures.VoiceSend v = offered(parse(voice("{\"audio/ogg\":0,\"audio/ogg; codecs=opus\":2}")));
        assertEquals("audio/ogg; codecs=opus", v.mime());
        assertTrue(v.voice());
    }

    @Test
    void telegramAndInstagramTakeOgg() throws Exception {
        assertEquals("audio/ogg", offered(parse(voice("{\"audio/mp4\":2,\"audio/mpeg\":2,\"audio/ogg\":2}"))).mime());
        assertEquals("audio/ogg", offered(parse(voice("{\"audio/mp4\":2,\"audio/ogg\":1}"))).mime());
    }

    @Test
    void aRoomThatRejectsOggIsNotOffered() throws Exception {
        assertNull(parse(voice("{\"audio/mp4\":2,\"audio/ogg\":-2}")).voiceSend());
    }

    @Test
    void aNetworkWithoutFilesIsNotOffered() throws Exception {
        assertNull(parse("{\"edit\":1}").voiceSend());
    }

    @Test
    void discordHasNoVoiceNotesButTakesTheFile() {
        RoomFeatures.VoiceSend v = offered(RoomFeatures.discord());
        assertEquals("audio/ogg", v.mime());
        assertFalse(v.voice());
    }

    @Test
    void anUnknownServerGetsAVoiceNote() {
        assertTrue(offered(RoomFeatures.UNKNOWN).voice());
    }
}

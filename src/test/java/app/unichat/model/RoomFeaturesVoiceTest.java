package app.unichat.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With what each bridge really publishes. */
class RoomFeaturesVoiceTest {
    private static RoomFeatures parse(String json) throws Exception {
        return RoomFeatures.parse(new ObjectMapper().readTree(json));
    }

    private static String voice(String mimes) {
        return "{\"file\":{\"org.matrix.msc3245.voice\":{\"mime_types\":" + mimes + "},\"m.audio\":{\"mime_types\":{\"audio/mp4\":2,\"audio/mpeg\":2}}}}";
    }

    @Test
    void whatsAppWantsTheCodecNamed() throws Exception {
        RoomFeatures.VoiceSend v = parse(voice("{\"audio/ogg\":0,\"audio/ogg; codecs=opus\":2}")).voiceSend();
        assertEquals("audio/ogg; codecs=opus", v.mime());
        assertTrue(v.voice());
    }

    @Test
    void telegramAndInstagramTakeOgg() throws Exception {
        assertEquals("audio/ogg", parse(voice("{\"audio/mp4\":2,\"audio/mpeg\":2,\"audio/ogg\":2}")).voiceSend().mime());
        assertEquals("audio/ogg", parse(voice("{\"audio/mp4\":2,\"audio/ogg\":1}")).voiceSend().mime());
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
        RoomFeatures.VoiceSend v = RoomFeatures.discord().voiceSend();
        assertEquals("audio/ogg", v.mime());
        assertFalse(v.voice());
    }

    @Test
    void anUnknownServerGetsAVoiceNote() {
        assertTrue(RoomFeatures.UNKNOWN.voiceSend().voice());
    }
}

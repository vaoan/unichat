package app.unichat.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SaveKindTest {
    private static Message media(MessageType type, String mime) {
        Message m = new Message("m", "!c", "@a", "A", false, Instant.now());
        m.setType(type);
        m.setMimeType(mime);
        m.setMediaUrl("mxc://s/x");
        return m;
    }

    @Test
    void eachMessageItsKindStickersNone() throws Exception {
        assertEquals(SaveKind.PHOTOS, SaveKind.of(media(MessageType.IMAGE, "image/jpeg")));
        assertEquals(SaveKind.GIFS, SaveKind.of(media(MessageType.IMAGE, "image/gif")));
        assertEquals(SaveKind.VIDEOS, SaveKind.of(media(MessageType.VIDEO, "video/mp4")));
        Message gifVideo = media(MessageType.VIDEO, "video/mp4");
        gifVideo.setMediaInfo(new ObjectMapper().readTree("{\"fi.mau.gif\":true}"));
        assertEquals(SaveKind.GIFS, SaveKind.of(gifVideo), "a GIF the bridge sends as video");
        assertEquals(SaveKind.AUDIO, SaveKind.of(media(MessageType.AUDIO, "audio/ogg")));
        assertEquals(SaveKind.DOCUMENTS, SaveKind.of(media(MessageType.FILE, "application/pdf")));
        assertNull(SaveKind.of(media(MessageType.STICKER, "image/webp")));
        Message text = new Message("t", "!c", "@a", "A", false, Instant.now());
        text.setType(MessageType.TEXT);
        assertNull(SaveKind.of(text));
    }
}

package app.unichat.ui.media;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GalleryTest {
    private static Message msg(String id, MessageType type, String name) {
        Message m = new Message(id, "!c", "@a", "A", false, Instant.EPOCH);
        m.setType(type);
        m.setMediaUrl("mxc://s/" + id);
        m.setFileName(name);
        return m;
    }

    @Test
    void photosAndPicturesSentAsDocumentsInOrder() {
        Message photo = msg("$1", MessageType.IMAGE, "a.jpg");
        Message text = msg("$2", MessageType.TEXT, null);
        text.setMediaUrl(null);
        Message doc = msg("$3", MessageType.FILE, "IMG_0019.webp");
        Message pdf = msg("$4", MessageType.FILE, "informe.pdf");
        Message sticker = msg("$5", MessageType.STICKER, "s.webp");
        Message video = msg("$6", MessageType.VIDEO, "v.mp4");
        List<Message> pics = Gallery.pictures(List.of(photo, text, doc, pdf, sticker, video));
        assertEquals(List.of(photo, doc), pics);
        assertEquals(1, Gallery.indexOf(pics, doc));
        assertEquals(-1, Gallery.indexOf(pics, pdf));
    }
}

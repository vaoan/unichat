package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaSaverTest {
    private static Message media(MessageType type, String fileName, String mime) {
        Message m = new Message("$1", "!c", "@a", "Ana", false, Instant.parse("2026-10-26T15:04:05Z"));
        m.setType(type);
        m.setMediaUrl("mxc://s/abc");
        m.setMimeType(mime);
        m.setFileName(fileName);
        return m;
    }

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Test
    void keepsTheOriginalName() {
        assertEquals("factura marzo.pdf", MediaSaver.suggestedName(media(MessageType.FILE, "factura marzo.pdf", "application/pdf"), BOGOTA));
    }

    @Test
    void neverAPathOutsideTheChosenFolder() {
        assertEquals("passwd", MediaSaver.suggestedName(media(MessageType.FILE, "../../etc/passwd", null), BOGOTA));
        assertEquals("evil.exe", MediaSaver.suggestedName(media(MessageType.FILE, "C:\\Windows\\evil.exe", null), BOGOTA));
    }

    @Test
    void namedFromTheMessageTimeWhenThereIsNoName() {
        assertEquals("imagen-20261026-100405.jpg", MediaSaver.suggestedName(media(MessageType.IMAGE, null, "image/jpeg"), BOGOTA));
        assertEquals("sticker-20261026-100405.webp", MediaSaver.suggestedName(media(MessageType.STICKER, "sticker", "image/webp"), BOGOTA));
        assertEquals("video-20261026-100405.mp4", MediaSaver.suggestedName(media(MessageType.VIDEO, "", "video/mp4"), BOGOTA));
    }

    @Test
    void addsTheExtensionWhenTheNameHasNone() {
        assertEquals("foto.png", MediaSaver.suggestedName(media(MessageType.IMAGE, "foto", "image/png"), BOGOTA));
    }
}

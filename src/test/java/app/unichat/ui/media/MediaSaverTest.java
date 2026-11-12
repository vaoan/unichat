package app.unichat.ui.media;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void theMimeParametersDontHideTheExtension() {
        assertEquals("nota.ogg", MediaSaver.suggestedName(media(MessageType.AUDIO, "nota", "audio/ogg; codecs=opus"), BOGOTA));
        assertEquals("audio-20261026-100405.ogg", MediaSaver.suggestedName(media(MessageType.AUDIO, null, "AUDIO/OGG;codecs=opus"), BOGOTA));
    }

    @Test
    void knowsWhatTheDownloaderKnows() {
        assertEquals("grabacion.wav", MediaSaver.suggestedName(media(MessageType.AUDIO, "grabacion", "audio/wav"), BOGOTA));
        assertEquals("clip.mov", MediaSaver.suggestedName(media(MessageType.VIDEO, "clip", "video/quicktime"), BOGOTA), "a saved file keeps its real type");
    }

    @Test
    void namesWindowsWouldMisreadAreCleaned() {
        assertEquals("C_evil.exe", MediaSaver.suggestedName(media(MessageType.FILE, "C:evil.exe", null), BOGOTA));
        assertEquals("foo.txt_ads", MediaSaver.suggestedName(media(MessageType.FILE, "foo.txt:ads", null), BOGOTA));
        assertEquals("a_b_.pdf", MediaSaver.suggestedName(media(MessageType.FILE, "a<b>.pdf", null), BOGOTA));
        assertEquals("_CON.txt", MediaSaver.suggestedName(media(MessageType.FILE, "CON.txt", null), BOGOTA));
        assertEquals("nota", MediaSaver.suggestedName(media(MessageType.FILE, "nota. . ", null), BOGOTA));
        assertTrue(MediaSaver.suggestedName(media(MessageType.FILE, "x".repeat(400) + ".pdf", null), BOGOTA).length() <= 200);
        assertTrue(MediaSaver.suggestedName(media(MessageType.FILE, "x".repeat(400) + ".pdf", null), BOGOTA).endsWith(".pdf"));
    }
}

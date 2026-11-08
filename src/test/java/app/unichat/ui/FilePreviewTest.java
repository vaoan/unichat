package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class FilePreviewTest {

    private static Message file(String name, String mime, long size) {
        Message m = new Message("$1", "!c", "@a", "A", false, Instant.EPOCH);
        m.setType(MessageType.FILE);
        m.setMediaUrl("mxc://server/file");
        m.setFileName(name);
        m.setMimeType(mime);
        m.setFileSize(size);
        return m;
    }

    @Test
    void aPhotoSentAsADocumentShowsItself() {
        assertEquals("mxc://server/file", FilePreview.url(file("IMG_0019.jpg", "image/jpeg", 2_000_000)));
        assertEquals("mxc://server/file", FilePreview.url(file("foto.webp", "application/octet-stream", 1000)),
                "a document's MIME often says nothing: the name does");
    }

    @Test
    void theAttachedThumbnailWinsAndOtherFilesKeepTheirIcon() {
        Message video = file("clip.mp4", "video/mp4", 50_000_000);
        video.setMediaInfo(JsonNodeFactory.instance.objectNode().put("thumbnail_url", "mxc://server/thumb"));
        assertEquals("mxc://server/thumb", FilePreview.url(video));
        assertNull(FilePreview.url(file("clip.mp4", "video/mp4", 1000)));
        assertNull(FilePreview.url(file("informe.pdf", "application/pdf", 1000)));
        assertNull(FilePreview.url(file("IMG_1.heic", "image/heic", 1000)), "JavaFX can't read it");
    }

    @Test
    void ourCopyAndTheServersAreTheSamePreview() {
        Message local = file("IMG_0019.webp", "image/webp", 1_381_724);
        local.setMediaUrl("file:///Users/x/IMG_0019.webp");
        Message server = file("IMG_0019.webp", "image/webp", 1_381_724);
        assertEquals(FilePreview.key(local), FilePreview.key(server), "no flash when the echo becomes the real message");
        assertFalse(FilePreview.key(server).equals(FilePreview.key(file("IMG_0020.webp", "image/webp", 1_381_724))));
    }

    @Test
    void aHugePictureIsNotDownloadedJustForItsPreview() {
        assertNull(FilePreview.url(file("panorama.png", "image/png", FilePreview.MAX_IMAGE_BYTES + 1)));
    }
}

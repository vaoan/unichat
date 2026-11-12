package app.unichat.ui.emoji;

import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A sticker of one's own that went as a converted copy is, in the library, the sticker it was made from. */
class MediaItemsTest {
    private static final MediaLibrary.Item ORIGINAL =
            new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://t/1", "video/webm", 512, 512, null);

    private static Message copy(boolean outgoing) {
        Message m = new Message("$1", "!c", "@x", "X", outgoing, Instant.EPOCH);
        m.setType(MessageType.STICKER);
        m.setMediaUrl("mxc://ours/gif");
        m.setMimeType("image/gif");
        ObjectNode info = new ObjectMapper().createObjectNode();
        info.set(MediaLibrary.ORIGINAL, MediaLibrary.json(ORIGINAL));
        m.setMediaInfo(info);
        return m;
    }

    @Test
    void ourOwnCopyIsTheOriginal() {
        assertEquals(ORIGINAL, MediaItems.of(copy(true)));
    }

    @Test
    void someoneElsesMessageCantNameAnotherSticker() {
        assertEquals("mxc://ours/gif", MediaItems.of(copy(false)).url(), "what a stranger writes there isn't trusted");
    }
}

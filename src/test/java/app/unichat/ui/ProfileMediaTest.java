package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileMediaTest {
    private static Message m(String id, MessageType type, String body, boolean media) {
        Message x = new Message(id, "!c", "@a", "Ana", false, Instant.parse("2026-11-10T10:00:00Z"));
        x.setType(type);
        x.setBody(body);
        if (media) x.setMediaUrl("mxc://s/" + id);
        return x;
    }

    @Test
    void eachThingOnItsShelfNewestFirstWithoutStickersNorDeleted() {
        Message gone = m("p0", MessageType.IMAGE, null, true);
        gone.setDeleted(true);
        ProfileMedia.Contents c = ProfileMedia.Contents.of(List.of(
                m("p1", MessageType.IMAGE, null, true), gone, m("v1", MessageType.VIDEO, null, true),
                m("s1", MessageType.STICKER, null, true), m("t1", MessageType.TEXT, "mira https://a.org/x y https://b.org", false),
                m("f1", MessageType.FILE, null, true), m("a1", MessageType.AUDIO, null, true), m("p2", MessageType.IMAGE, null, true)));
        assertEquals(List.of("p2", "p1"), c.photos().stream().map(Message::id).toList());
        assertEquals(List.of("v1"), c.videos().stream().map(Message::id).toList());
        assertEquals(List.of("a1"), c.audios().stream().map(Message::id).toList(), "audio on its own shelf");
        assertEquals(List.of("f1"), c.files().stream().map(Message::id).toList(), "and documents on theirs");
        assertEquals(List.of("https://a.org/x", "https://b.org"), c.links().stream().map(ProfileMedia.Link::url).toList());
        assertEquals(2, c.count(ProfileMedia.Shelf.PHOTOS));
    }
}

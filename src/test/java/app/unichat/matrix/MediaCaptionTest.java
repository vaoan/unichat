package app.unichat.matrix;

import app.unichat.Present;
import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A photo and its text in one event, as X's bridge sends them (MSC2530: {@code body} is the caption when a
 * {@code filename} is given): nothing of either is lost. The shape is the bridge's own, with a synthetic text.
 */
class MediaCaptionTest {
    private static final String ROOM = "!x:example.org";
    private static final String X_USER = "@twitter_1000000000000000001:example.org";
    private static final String CAPTION = "If you want our artist to draw it for you, here are three styles to choose from. "
            + "Each comes with front and back views, colours and a small sketch of the details.\n\n"
            + "Tell us which one you like and we'll send the price and the dates. Thanks for asking!";

    /** The X bridge's photo with text: m.image, the text as body, the attachment's id as filename, its own metadata. */
    private static String xPhotoWithText(String body) {
        return """
                {"type":"m.room.message","event_id":"$photo","sender":"%s","origin_server_ts":1000,
                 "content":{"msgtype":"m.image","body":%s,
                  "filename":"0a1b2c3d-0000-4000-8000-000000000001.jpg",
                  "info":{"h":767,"mimetype":"image/jpeg","w":1600},
                  "url":"mxc://media-twitter.example.org/AbCdEf0123",
                  "m.mentions":{},"com.beeper.disappearing_timer":{},
                  "com.beeper.xchat.original_attachments":[{"media":{"attachment_id":"0a1b2c3d-0000-4000-8000-000000000001",
                   "dimensions":{"height":767,"width":1600},"filename":"设定.jpg","filesize_bytes":275356,
                   "media_hash_key":"abcdefghij","plaintext_sha256":"�\\u001e:]�\\u0007��i�04/","type":1}}]}}
                """.formatted(X_USER, MatrixClient.JSON.valueToTree(body).toString());
    }

    private static List<Message> sync(String event) throws com.fasterxml.jackson.core.JsonProcessingException {
        SyncProcessor proc = new SyncProcessor("@me:example.org");
        proc.process(MatrixClient.JSON.readTree("""
                {"rooms":{"join":{"%s":{"timeline":{"events":[%s],"prev_batch":"p"}}}}}
                """.formatted(ROOM, event)), false);
        return Present.of(proc.room(ROOM)).timeline;
    }

    @Test
    void anXPhotoWithItsTextKeepsThePictureAndTheWholeText() throws Exception {
        List<Message> got = sync(xPhotoWithText(CAPTION));
        assertEquals(1, got.size(), "one event, one message");
        Message m = got.get(0);
        assertEquals(MessageType.IMAGE, m.type());
        assertEquals("mxc://media-twitter.example.org/AbCdEf0123", m.mediaUrl(), "the picture");
        assertEquals(1600, m.width());
        assertEquals(767, m.height());
        assertEquals("0a1b2c3d-0000-4000-8000-000000000001.jpg", m.fileName(), "the name is the filename, not the text");
        assertEquals(CAPTION, m.caption(), "the whole text, as its caption");
    }

    @Test
    void theChatListSaysItsAPhotoAndWhatItSays() throws Exception {
        SyncProcessor proc = new SyncProcessor("@me:example.org");
        proc.process(MatrixClient.JSON.readTree("""
                {"rooms":{"join":{"%s":{"timeline":{"events":[%s],"prev_batch":"p"}}}}}
                """.formatted(ROOM, xPhotoWithText("Our styles"))), false);
        Chat c = Present.of(proc.room(ROOM)).chat;
        assertEquals("📷 Foto: Our styles", c.lastPreview());
    }

    @Test
    void aCaptionsFormattingIsKept() throws Exception {
        Message m = sync("""
                {"type":"m.room.message","event_id":"$p","sender":"%s","origin_server_ts":1000,
                 "content":{"msgtype":"m.image","body":"see www.example.org","filename":"a.jpg","url":"mxc://s/1",
                  "format":"org.matrix.custom.html","formatted_body":"see <b>www.example.org</b>"}}
                """.formatted(X_USER)).get(0);
        assertEquals("see www.example.org", m.caption());
        assertEquals("see <b>www.example.org</b>", m.html());
    }

    @Test
    void aFileNamedByItsBodyHasNoCaption() throws Exception {
        Message named = sync("""
                {"type":"m.room.message","event_id":"$p","sender":"%s","origin_server_ts":1000,
                 "content":{"msgtype":"m.image","body":"photo.jpg","url":"mxc://s/1",
                  "format":"org.matrix.custom.html","formatted_body":"<b>photo.jpg</b>"}}
                """.formatted(X_USER)).get(0);
        assertEquals("photo.jpg", named.fileName());
        assertNull(named.caption(), "body is the name when no filename comes (MSC2530)");
        assertNull(named.html(), "nor is its HTML drawn under the picture");
        Message same = sync("""
                {"type":"m.room.message","event_id":"$p","sender":"%s","origin_server_ts":1000,
                 "content":{"msgtype":"m.file","body":"doc.pdf","filename":"doc.pdf","url":"mxc://s/1"}}
                """.formatted(X_USER)).get(0);
        assertNull(same.caption(), "a body equal to the filename is the name");
        assertTrue(same.preview().contains("doc.pdf"));
    }

    @Test
    void aStickersDescriptionIsNoCaption() throws Exception {
        Message s = sync("""
                {"type":"m.sticker","event_id":"$s","sender":"%s","origin_server_ts":1000,
                 "content":{"body":"a waving cat","filename":"cat.webp","url":"mxc://s/1"}}
                """.formatted(X_USER)).get(0);
        assertNull(s.caption());
    }
}

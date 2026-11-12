package app.unichat.matrix;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a forwarded copy says: the message as it showed, nobody pinged, nothing it answered. */
class ForwardedContentTest {
    private static Message from(MessageType type) {
        Message m = new Message("$1", "!a:s", "@discord_1:s", "Ana", false, Instant.EPOCH);
        m.setType(type);
        return m;
    }

    @Test
    void textKeepsItsFormattingButMentionsNoOne() {
        Message m = from(MessageType.TEXT);
        m.setBody("hola Bob, mira esto");
        m.setHtml("hola <a href=\"https://matrix.to/#/@discord_2:s\">Bob</a>, <b>mira</b> <a href=\"https://x.com/a\">esto</a>");
        m.setReplyToId("$0");
        JsonNode c = ForwardedContent.of(m, false);
        assertEquals("m.text", c.path("msgtype").asText());
        assertEquals("hola Bob, mira esto", c.path("body").asText());
        assertEquals("hola Bob, <b>mira</b> <a href=\"https://x.com/a\">esto</a>", c.path("formatted_body").asText(),
                "a person's mention as plain text: forwarded to Discord it would ping them");
        assertTrue(c.path("m.mentions").isObject() && c.path("m.mentions").isEmpty(), "no one is mentioned");
        assertTrue(c.path("m.relates_to").isMissingNode(), "a forward answers nothing");
    }

    @Test
    void aReplyGoesWithoutTheQuoteOfWhatItAnswered() {
        Message m = from(MessageType.TEXT);
        m.setBody("sí, a las 8");
        m.setHtml("<mx-reply><blockquote>In reply to <a href=\"https://matrix.to/#/@x:s\">X</a><br>¿a qué hora?</blockquote></mx-reply>sí, a las <b>8</b>");
        assertEquals("sí, a las <b>8</b>", ForwardedContent.of(m, false).path("formatted_body").asText());
    }

    @Test
    void anEmoteStaysOne() {
        Message m = from(MessageType.EMOTE);
        m.setBody("saluda");
        assertEquals("m.emote", ForwardedContent.of(m, false).path("msgtype").asText());
    }

    @Test
    void aPhotoKeepsItsCaptionNameAndInfo() throws Exception {
        Message m = from(MessageType.IMAGE);
        m.setBody("la foto");
        m.setFileName("foto.jpg");
        m.setMediaUrl("mxc://s/foto");
        m.setMimeType("image/jpeg");
        m.setSize(800, 600);
        m.setMediaInfo(MatrixClient.JSON.readTree("{\"mimetype\":\"image/jpeg\",\"w\":800,\"h\":600,\"thumbnail_url\":\"mxc://media-telegram.localhost/t\"}"));
        JsonNode kept = ForwardedContent.of(m, false);
        assertEquals("m.image", kept.path("msgtype").asText());
        assertEquals("la foto", kept.path("body").asText());
        assertEquals("foto.jpg", kept.path("filename").asText());
        assertEquals("mxc://s/foto", kept.path("url").asText());
        assertEquals(800, kept.path("info").path("w").asInt());
        assertFalse(kept.path("info").path("thumbnail_url").isMissingNode());
        JsonNode copied = ForwardedContent.of(m, true);
        assertTrue(copied.path("info").path("thumbnail_url").isMissingNode(), "a copy's thumbnail would be on the other bridge too");
    }

    @Test
    void aFileWithoutCaptionIsCalledByItsName() {
        Message m = from(MessageType.FILE);
        m.setFileName("informe.pdf");
        m.setMediaUrl("mxc://s/pdf");
        m.setMimeType("application/pdf");
        m.setFileSize(5000);
        JsonNode c = ForwardedContent.of(m, false);
        assertEquals("m.file", c.path("msgtype").asText());
        assertEquals("informe.pdf", c.path("body").asText());
        assertEquals("application/pdf", c.path("info").path("mimetype").asText());
        assertEquals(5000, c.path("info").path("size").asLong());
    }

    /**
     * A bridge runs as an admin command any message of mine that starts with its prefix ("!whatsapp …"), in any room: a
     * stranger's text forwarded as mine would delete every WhatsApp chat.
     */
    @Test
    void aStrangersBridgeCommandGoesAsTextNotACommand() {
        Message text = from(MessageType.TEXT);
        text.setBody("!whatsapp delete-all-portals");
        assertFalse(ForwardedContent.of(text, false).path("body").asText().startsWith("!"));
        Message file = from(MessageType.FILE);
        file.setFileName("!telegram logout");
        file.setMediaUrl("mxc://s/f");
        assertFalse(ForwardedContent.of(file, false).path("body").asText().startsWith("!"), "a file's name is its plain text");
    }

    @Test
    void aStrangersMarkCantNameAnotherSticker() throws Exception {
        Message m = from(MessageType.IMAGE);
        m.setMediaUrl("mxc://s/a");
        m.setMediaInfo(MatrixClient.JSON.readTree("{\"app.unichat.original\":{\"kind\":\"GIF\",\"url\":\"mxc://s/b\"}}"));
        assertTrue(ForwardedContent.of(m, false).path("info").path("app.unichat.original").isMissingNode(),
                "seen back as mine, it would be trusted");
    }

    @Test
    void aFileNameCantHideItsExtension() {
        Message m = from(MessageType.FILE);
        m.setFileName("factura\u202Efdp.exe");
        m.setMediaUrl("mxc://s/f");
        JsonNode c = ForwardedContent.of(m, false);
        assertEquals("facturafdp.exe", c.path("filename").asText(), "the right-to-left override showed it as 'facturaexe.pdf'");
        assertEquals("facturafdp.exe", c.path("body").asText());
    }

    @Test
    void textNobodySawDoesntGoAndNoMentionFormGetsThrough() {
        assertEquals("hola Bob y Eve y Ann", ForwardedContent.withoutMentions(
                "hola <a href=\"https://matrix.to/#/%40bob:s\">Bob</a> y <a href=\"matrix:u/eve:s\">Eve</a>"
                + " y <a href=\"http://www.matrix.to/#/@ann:s\">Ann</a><script>secreto</script><style>x</style>"));
    }
}

package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A11yTextTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Instant NOON = Instant.parse("2030-03-04T12:30:00Z");

    private static Chat chat(String title, Network network) {
        Chat c = new Chat("!x");
        c.setTitle(title);
        c.setNetwork(network);
        return c;
    }

    private static Message message(boolean outgoing, String body) {
        Message m = new Message("$1", "!x", "@laura", "Laura", outgoing, NOON);
        m.setType(MessageType.TEXT);
        m.setBody(body);
        return m;
    }

    @Test
    void chatDescriptionMentionsNetworkUnreadAndState() {
        Chat c = chat("Familia", Network.WHATSAPP);
        c.setGroup(true);
        c.setUnreadCount(3);
        c.setMuted(true);
        c.setLastSenderName("Laura");
        c.setLastPreview("hola");
        c.setLastActivity(NOON);
        String d = A11yText.chat(c, null, NOON, UTC);
        assertTrue(d.startsWith("Familia, WhatsApp, grupo, 3 mensajes sin leer"), d);
        assertTrue(d.contains("silenciado") && d.contains("Laura: hola"), d);
    }

    @Test
    void chatDescriptionNamesTheAccountWhenGiven() {
        assertTrue(A11yText.chat(chat("Ana", Network.TELEGRAM), "Trabajo", NOON, UTC).startsWith("Ana, Telegram (Trabajo)"));
    }

    @Test
    void offerSaysWhatClickingDoes() {
        Chat c = chat("Juegos", Network.DISCORD);
        assertEquals("Juegos, servidor de Discord sin agregar, Agregar", A11yText.offer(c, false));
        assertEquals("Juegos, servidor de Discord sin agregar, Agregando", A11yText.offer(c, true));
    }

    @Test
    void serverSumsItsChannels() {
        Chat c = chat("Juegos", Network.DISCORD);
        Chat latest = chat("#general", Network.DISCORD);
        latest.setLastPreview("hola");
        assertEquals("Juegos, servidor de Discord, 4 sin leer. #general: hola. Abre sus canales", A11yText.server(c, 4, latest));
        c.setMuted(true);
        assertEquals("Juegos, servidor de Discord, silenciado. Abre sus canales", A11yText.server(c, 0, null));
        assertEquals("Equipo, grupo de Telegram. Abre sus canales", A11yText.server(chat("Equipo", Network.TELEGRAM), 0, null));
    }

    @Test
    void unjoinedChannelOffersToJoin() {
        assertEquals("#anuncios, canal sin unirte, Unirse", A11yText.unjoined(chat("#anuncios", Network.DISCORD)));
    }

    @Test
    void messageSaysWhoWhenAndWhat() {
        String expected = "Laura, " + Formats.time(NOON, UTC) + ": hola";
        assertEquals(expected, A11yText.message(message(false, "hola"), null, null, UTC));
    }

    @Test
    void ownMessageSaysItsStatus() {
        Message m = message(true, "hola");
        m.setStatus(MessageStatus.FAILED);
        String d = A11yText.message(m, null, null, UTC);
        assertTrue(d.startsWith("Tú, "), d);
        assertTrue(d.endsWith("hola. No se pudo enviar"), d);
    }

    @Test
    void messageSaysWhatItAnswers() {
        assertTrue(A11yText.message(message(false, "sí"), message(true, "¿vienes?"), null, UTC).contains("respondiendo a ti. sí"));
        assertTrue(A11yText.message(message(false, "sí"), message(false, "¿vienes?"), null, UTC).contains("respondiendo a Laura. sí"));
    }

    @Test
    void systemMessageIsJustItsText() {
        Message m = message(false, "Laura se unió");
        m.setType(MessageType.SYSTEM);
        assertEquals("Laura se unió", A11yText.message(m, null, null, UTC));
    }

    @Test
    void voiceNoteReadsItsTranscript() {
        Message m = message(false, "");
        m.setType(MessageType.AUDIO);
        assertTrue(A11yText.message(m, null, "nos vemos", UTC).contains(". Transcripción: nos vemos"));
        assertTrue(A11yText.message(m, null, "", UTC).contains(". Transcripción: sin voz"));
        assertFalse(A11yText.message(m, null, null, UTC).contains("Transcripción"));
    }

    @Test
    void messageListsItsReactionsAndEdit() {
        Message m = message(false, "hola");
        m.setEdited(true);
        m.addReaction("👍", "@a");
        m.addReaction("👍", "@b");
        String d = A11yText.message(m, null, null, UTC);
        assertTrue(d.endsWith("hola, editado. Reacciones: 👍 2"), d);
    }

    @Test
    void spokenHidesThePreviewWhenAsked() {
        Chat group = chat("Familia", Network.WHATSAPP);
        group.setGroup(true);
        Message m = message(false, "hola");
        assertEquals("Mensaje de Laura en Familia, WhatsApp: " + m.preview(), A11yText.spoken(group, m, true));
        assertEquals("Mensaje de Laura en Familia, WhatsApp", A11yText.spoken(group, m, false));
        assertEquals("Mensaje de Ana, Telegram", A11yText.spoken(chat("Ana", Network.TELEGRAM), m, false));
    }
}

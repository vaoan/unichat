package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class A11yTextTest {
    @Test
    void chatDescriptionMentionsNetworkUnreadAndState() {
        Chat c = new Chat("!x");
        c.setTitle("Familia");
        c.setNetwork(Network.WHATSAPP);
        c.setGroup(true);
        c.setUnreadCount(3);
        c.setMuted(true);
        c.setLastSenderName("Laura");
        c.setLastPreview("hola");
        c.setLastActivity(Instant.now());
        String d = ChatListPane.describe(c);
        assertTrue(d.startsWith("Familia, WhatsApp, grupo, 3 mensajes sin leer"), d);
        assertTrue(d.contains("silenciado") && d.contains("Laura: hola"), d);
    }
}

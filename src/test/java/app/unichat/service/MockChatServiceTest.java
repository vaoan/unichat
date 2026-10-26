package app.unichat.service;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockChatServiceTest {
    @Test
    void sendEditReactDeleteAndChatState() {
        MockChatService svc = new MockChatService();
        Chat mama = svc.chat("!mama:demo");
        assertTrue(mama.isUnread());
        svc.markRead(mama.id());
        assertFalse(mama.isUnread());

        svc.sendText(mama.id(), "hola", null).join();
        List<Message> msgs = svc.messages(mama.id());
        Message sent = msgs.get(msgs.size() - 1);
        assertTrue(sent.outgoing());
        assertEquals(MessageStatus.SENDING, sent.status());
        assertEquals("hola", mama.lastPreview());

        svc.editMessage(mama.id(), sent.id(), "hola!").join();
        assertTrue(sent.edited());
        svc.toggleReaction(mama.id(), sent.id(), "👍").join();
        assertTrue(sent.reactions().get("👍").contains(svc.myUserId()));
        svc.toggleReaction(mama.id(), sent.id(), "👍").join();
        assertTrue(sent.reactions().isEmpty());
        svc.deleteMessage(mama.id(), sent.id()).join();
        assertTrue(sent.deleted());

        svc.setArchived(mama.id(), true).join();
        assertTrue(mama.archived());
        svc.stop();
    }

    @Test
    void severalAccountsPerNetwork() {
        MockChatService svc = new MockChatService();
        List<app.unichat.model.Account> wa = svc.accounts().stream()
                .filter(a -> a.network() == app.unichat.model.Network.WHATSAPP).toList();
        assertEquals(2, wa.size(), "demo starts with two WhatsApp accounts");
        assertEquals("whatsapp:trabajo", svc.chat("!andrea:demo").accountId());
        assertEquals("whatsapp:personal", svc.chat("!mama:demo").accountId());

        String bot = svc.addAccount(app.unichat.model.Network.WHATSAPP).join();
        assertTrue(svc.chat(bot).bridgeBot());
        assertEquals(3, svc.accounts().stream().filter(a -> a.network() == app.unichat.model.Network.WHATSAPP).count(),
                "a pending login is listed until it finishes");

        app.unichat.model.Account work = wa.stream().filter(a -> a.id().equals("whatsapp:trabajo")).findFirst().orElseThrow();
        svc.disconnectAccount(work).join();
        assertEquals(null, svc.chat("!andrea:demo"), "chats of the disconnected account go away");
        assertTrue(svc.chat("!mama:demo") != null, "the other account keeps its chats");
        svc.stop();
    }
}

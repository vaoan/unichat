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
}

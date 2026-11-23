package app.unichat.service;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockChatServiceTest {
    /** Every name and text the demo shows, and the voice note's file, all in the UI language through demo.* keys. */
    @Test
    void theDemoSpeaksTheUiLanguage() throws Exception {
        String before = app.unichat.support.I18n.locale().getLanguage();
        try {
            app.unichat.support.I18n.use("en");
            MockChatService en = new MockChatService();
            List<String> shown = shownTexts(en);
            assertTrue(shown.containsAll(List.of("Mom", "Andrew Miller", "Sophie", "Anna Marie", "Camille Rogers")), shown.toString());
            assertTrue(shown.stream().noneMatch(t -> t.matches("(?s).*[áéíóúñ¿¡].*")),
                    shown.stream().filter(t -> t.matches("(?s).*[áéíóúñ¿¡].*")).toList().toString());
            assertTrue(en.messages("!family:demo").stream().anyMatch(m -> "demo://voice-note.ogg".equals(m.mediaUrl())));
            assertTrue(java.nio.file.Files.size(DemoMedia.demoAudio("voice-note.ogg")) > 0, "the renamed resource is there");
            en.stop();

            app.unichat.support.I18n.use("es");
            MockChatService es = new MockChatService();
            assertTrue(shownTexts(es).containsAll(List.of("Mamá", "Andrés Mejía", "Sofía", "Ana María")));
            es.stop();
        } finally {
            app.unichat.support.I18n.use(before);
        }
    }

    private static List<String> shownTexts(MockChatService svc) throws InterruptedException, ExecutionException {
        var out = new java.util.ArrayList<String>();
        for (Chat c : svc.chats()) {
            out.add(c.title());
            c.members().forEach(m -> out.add(m.name()));
            svc.messages(c.id()).forEach(m -> out.add(m.body() == null ? "" : m.body()));
        }
        var wa = svc.accounts().stream().filter(a -> a.network() == app.unichat.model.Network.WHATSAPP).findFirst().orElseThrow();
        svc.contacts(wa).get().forEach(c -> out.add(c.name()));
        return out;
    }

    @Test
    void sendEditReactDeleteAndChatState() throws Exception {
        MockChatService svc = new MockChatService();
        Chat mama = svc.chat("!mom:demo");
        assertTrue(mama.isUnread());
        svc.markRead(mama.id());
        assertFalse(mama.isUnread());

        svc.sendText(mama.id(), "hola", null).get();
        List<Message> msgs = svc.messages(mama.id());
        Message sent = msgs.get(msgs.size() - 1);
        assertTrue(sent.outgoing());
        assertEquals(MessageStatus.SENDING, sent.status());
        assertEquals("hola", mama.lastPreview());

        svc.editMessage(mama.id(), sent.id(), "hola!").get();
        assertTrue(sent.edited());
        svc.toggleReaction(mama.id(), sent.id(), "👍").get();
        assertTrue(sent.reactions().get("👍").contains(svc.myUserId()));
        svc.toggleReaction(mama.id(), sent.id(), "👍").get();
        assertTrue(sent.reactions().isEmpty());
        svc.deleteMessage(mama.id(), sent.id()).get();
        assertTrue(sent.deleted());

        svc.setArchived(mama.id(), true).get();
        assertTrue(mama.archived());
        svc.stop();
    }

    @Test
    void severalAccountsPerNetwork() throws Exception {
        MockChatService svc = new MockChatService();
        List<app.unichat.model.Account> wa = svc.accounts().stream()
                .filter(a -> a.network() == app.unichat.model.Network.WHATSAPP).toList();
        assertEquals(2, wa.size(), "demo starts with two WhatsApp accounts");
        assertEquals("whatsapp:work", svc.chat("!andrea:demo").accountId());
        assertEquals("whatsapp:personal", svc.chat("!mom:demo").accountId());

        String bot = svc.addAccount(app.unichat.model.Network.WHATSAPP).get();
        assertTrue(svc.chat(bot).bridgeBot());
        assertEquals(3, svc.accounts().stream().filter(a -> a.network() == app.unichat.model.Network.WHATSAPP).count(),
                "a pending login is listed until it finishes");

        app.unichat.model.Account work = wa.stream().filter(a -> a.id().equals("whatsapp:work")).findFirst().orElseThrow();
        svc.disconnectAccount(work).get();
        assertNull(svc.chat("!andrea:demo"), "chats of the disconnected account go away");
        assertNotNull(svc.chat("!mom:demo"), "the other account keeps its chats");
        svc.stop();
    }
}

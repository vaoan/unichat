package app.unichat;

import app.unichat.assist.AiSettings;
import app.unichat.model.Message;
import app.unichat.service.MockChatService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Quitting or logging out sends what's waiting to be corrected, as written, before the service stops. */
class QuitSendsWhatsWaitingTest {
    @Test
    void whatsWaitingGoesAsWritten() {
        MockChatService svc = new MockChatService();
        svc.noAutoReplies();
        try {
            app.unichat.ui.timeline.SendQueueAccess.submitNeverAnswered(svc, "!family:demo", "creo q sí");
            UnichatApp.sendWhatsWaiting(svc);
            assertEquals("creo q sí", svc.messages("!family:demo").stream().filter(Message::outgoing).toList().getLast().body());
        } finally {
            svc.stop();
            AiSettings.setFixMode(AiSettings.FixMode.OFF);
        }
    }
}

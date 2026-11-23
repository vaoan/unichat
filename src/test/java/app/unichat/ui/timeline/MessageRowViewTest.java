package app.unichat.ui.timeline;

import app.unichat.WithLocale;
import app.unichat.model.MessageStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageRowViewTest {
    /** On a Turkish computer "FAILED" lower-cased to "faıled": the style sheet's red never reached a message that failed. */
    @Test
    void theStatusIconHasTheStyleSheetsClassInAnyLanguage() {
        assertEquals("status-failed", WithLocale.get(WithLocale.TURKISH, () -> MessageRowView.statusClass(MessageStatus.FAILED)));
        assertEquals("status-read", MessageRowView.statusClass(MessageStatus.READ));
    }
}

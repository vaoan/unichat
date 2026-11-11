package app.unichat.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A WhatsApp Business message shows its buttons as chips, in words, with where to press them, and nothing of the bridge's. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class BusinessButtonsUiTest {
    private TimelineWindow w;

    @AfterEach
    void close() throws Exception {
        if (w != null) w.close();
    }

    @Test
    void anOrderShowsItsButtonsAndANote() throws Exception {
        w = TimelineWindow.open(5);
        w.svc.receive(CHAT, m -> {
            m.setBody("Pedido");
            m.setButtons(List.of("review_and_pay", "Quiero el descuento"));
        });
        FxWait.until("it shows", w::showsNewest);
        FxWait.settled();
        List<String> chips = FxWait.fx(() -> w.list().lookupAll(".business-button").stream()
                .map(n -> ((javafx.scene.control.Label) n).getText()).toList());
        assertEquals(List.of("Revisar y pagar", "Quiero el descuento"), chips);
        assertTrue(FxWait.fx(() -> w.list().lookupAll(".business-note").stream().anyMatch(javafx.scene.Node::isVisible)), "where to press them");
        w.untilAtBottom(); // the chips are part of the bubble: the newest message is whole at the bottom
    }
}

package app.unichat.ui.timeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a WhatsApp Business button says (tests run in Spanish). */
class BusinessButtonsTest {
    @Test
    void internalNamesInWordsAndTheRestAsWritten() {
        assertEquals("Revisar y pagar", BusinessButtons.label("review_and_pay"));
        assertEquals("Ver pedido", BusinessButtons.label("review_order"));
        assertEquals("Abrir enlace", BusinessButtons.label("cta_url"));
        assertEquals("Galaxy message", BusinessButtons.label("galaxy_message"), "an unknown name, still in words");
        assertEquals("Quiero el descuento", BusinessButtons.label("Quiero el descuento"));
        assertEquals("OK", BusinessButtons.label("OK"));
    }
}

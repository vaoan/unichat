package app.unichat.matrix;

import app.unichat.model.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The WhatsApp bridge's text for a Business message's buttons becomes the message's own text and a list of buttons. */
class BusinessMessagesTest {
    private static final String NOTE = "Use the WhatsApp app to click buttons";

    @Test
    void aPaymentMessageWithAnInternalButtonName() {
        BusinessMessages.Parsed p = BusinessMessages.parse("Tu pedido está listo\n\n<review_and_pay>\n" + NOTE, null, "Pedido");
        assertEquals("Tu pedido está listo", p.body());
        assertEquals(List.of("review_and_pay"), p.buttons());
    }

    @Test
    void anOrderWhosePictureTheBridgeCantBring() {
        BusinessMessages.Parsed p = BusinessMessages.parse("Unsupported thumbnail message\n\n<review_and_pay>\n" + NOTE, null, "Pedido");
        assertEquals("Pedido", p.body());
        assertEquals(List.of("review_and_pay"), p.buttons());
    }

    @Test
    void aTemplateWithItsButtonsOwnWordsAndItsHtml() {
        String body = "¡Estamos de aniversario! Conoce nuestros descuentos. 👇\n\n<Quiero el descuento>\n<Más tarde>\n" + NOTE;
        String html = "¡Estamos de <b>aniversario</b>! Conoce nuestros descuentos. 👇<br><br>&lt;Quiero el descuento&gt;<br>"
                + "&lt;Más tarde&gt;<br>" + NOTE;
        BusinessMessages.Parsed p = BusinessMessages.parse(body, html, "Pedido");
        assertEquals("¡Estamos de aniversario! Conoce nuestros descuentos. 👇", p.body());
        assertEquals("¡Estamos de <b>aniversario</b>! Conoce nuestros descuentos. 👇", p.html());
        assertEquals(List.of("Quiero el descuento", "Más tarde"), p.buttons());
    }

    @Test
    void windowsLineEndsAndALinkButton() {
        String body = "¡30 años juntos!\r\n\r\nCompra en la tienda.\r\n\n<cta_url>\n" + NOTE;
        BusinessMessages.Parsed p = BusinessMessages.parse(body, null, "Pedido");
        assertEquals("¡30 años juntos!\r\n\r\nCompra en la tienda.\r", p.body());
        assertEquals(List.of("cta_url"), p.buttons());
    }

    @Test
    void onlyButtonsLeaveNoHtml() {
        BusinessMessages.Parsed p = BusinessMessages.parse("<payment_status>\n" + NOTE, "&lt;payment_status&gt;<br>" + NOTE, "Pedido");
        assertEquals("", p.body());
        assertNull(p.html());
        assertEquals(List.of("payment_status"), p.buttons());
    }

    /** As the bridge sent a promotion (an image with its caption) and an order: the message keeps only its own text. */
    @Test
    void theBridgesEventsBecomeMessagesWithButtons() throws Exception {
        Message promo = new Message("$p", "!c", "@whatsapp_x:localhost", "Tienda", false, Instant.EPOCH);
        SyncEvents.applyContent(promo, "m.image", "¡Promos!\n\n<Quiero el descuento>\n" + NOTE, MatrixClient.JSON.readTree("""
                {"msgtype":"m.image","fi.mau.whatsapp.business_message_type":"template","filename":"image.jpg",
                 "url":"mxc://localhost/x","info":{"w":1080,"h":1920,"mimetype":"image/jpeg"}}"""));
        assertEquals("¡Promos!", promo.body(), "the caption");
        assertEquals(List.of("Quiero el descuento"), promo.buttons());

        Message order = new Message("$o", "!c", "@whatsapp_x:localhost", "Tienda", false, Instant.EPOCH);
        String body = "Unsupported thumbnail message\n\n<review_and_pay>\n" + NOTE;
        SyncEvents.applyContent(order, "m.text", body, MatrixClient.JSON.readTree("""
                {"msgtype":"m.text","fi.mau.whatsapp.business_message_type":"interactive","format":"org.matrix.custom.html",
                 "formatted_body":"Unsupported thumbnail message<br><br>&lt;review_and_pay&gt;<br>Use the WhatsApp app to click buttons"}"""));
        assertEquals("Pedido", order.body());
        assertEquals("Pedido", order.html());
        assertEquals(List.of("review_and_pay"), order.buttons());
        assertTrue(!order.preview().contains("WhatsApp app") && !order.preview().contains("<"), order.preview());

        Message plain = new Message("$t", "!c", "@whatsapp_y:localhost", "Ana", false, Instant.EPOCH);
        SyncEvents.applyContent(plain, "m.text", "<hola>\n" + NOTE, MatrixClient.JSON.readTree("{\"msgtype\":\"m.text\"}"));
        assertEquals("<hola>\n" + NOTE, plain.body(), "without the bridge's mark, someone's text is theirs");
        assertTrue(plain.buttons().isEmpty());
    }

    @Test
    void textWithoutTheBridgesNoteIsLeftAlone() {
        String body = "Escribe <nombre> en la línea siguiente\n<así>";
        BusinessMessages.Parsed p = BusinessMessages.parse(body, null, "Pedido");
        assertEquals(body, p.body());
        assertTrue(p.buttons().isEmpty());
    }
}

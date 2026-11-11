package app.unichat.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The set a sticker belongs to, as Telegram's bridge marks it; the mark is the sender's to write, so only a set's link passes. */
class StickerPackTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String of(String bridged) throws Exception {
        return StickerPack.urlOf(JSON.readTree("{\"fi.mau.bridged_sticker\":" + bridged + "}"));
    }

    @Test
    void aStickersSetIsItsBridgesLink() throws Exception {
        assertEquals("https://t.me/addstickers/Gatitos_99", of("{\"network\":\"telegram\",\"id\":\"1\",\"pack_url\":\"https://t.me/addstickers/Gatitos_99\"}"));
        assertNull(of("{\"pack_url\":\"https://t.me/addstickers/../../x\"}"));
        assertNull(of("{\"pack_url\":\"https://evil.example/addstickers/x\"}"));
        assertNull(of("{\"pack_url\":\"https://t.me/addemoji/raros\"}"), "custom emoji sets aren't stickers");
        assertNull(StickerPack.urlOf(null));
        assertNull(StickerPack.urlOf(JSON.readTree("{\"mimetype\":\"image/webp\"}")));
    }
}

package app.unichat.ui;

import app.unichat.login.BridgeLogin;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeLoginPaneTest {
    private static BridgeLogin.Field field(String type, String pattern) {
        return new BridgeLogin.Field(type, "f", "Field", "", null, false, pattern, List.of());
    }

    @Test
    void phoneNeedsTheCountryCode() {
        assertTrue(BridgeLoginPane.invalid(field("phone_number", null), "3001234567").contains("código de país"));
        assertNull(BridgeLoginPane.invalid(field("phone_number", null), "+573001234567"));
        assertTrue(BridgeLoginPane.invalid(field("password", null), "").startsWith("Completa"));
    }

    @Test
    void bridgePatternIsChecked() {
        assertTrue(BridgeLoginPane.invalid(field("2fa_code", "^[0-9]{5}$"), "12a45") != null);
        assertNull(BridgeLoginPane.invalid(field("2fa_code", "^[0-9]{5}$"), "12345"));
        assertNull(BridgeLoginPane.invalid(field("token", "(?<bad"), "x"), "a pattern Java can't read is left to the bridge");
    }

    @Test
    void qrDecodesBackToTheBridgesData() throws Exception {
        String data = "2@AbCdEf,GhIj+kl/mn==,opQR,stUV==";
        WritableImage img = BridgeLoginPane.qr(data, 260);
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = img.getPixelReader().getArgb(x, y);
        LuminanceSource src = new RGBLuminanceSource(w, h, px);
        assertEquals(data, new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(src))).getText());
    }
}

package app.unichat.ui;

import app.unichat.service.MockChatService;
import app.unichat.settings.Settings;
import javafx.application.Platform;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ajustes → Redes: options that only make sense for one network (how WhatsApp gets animated stickers). */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class NetworkSettingsUiTest {
    @BeforeAll
    static void toolkit() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            if (SettingsWindow.current() != null) SettingsWindow.current().close();
            return null;
        });
        Settings.get().set("whatsapp.animatedStickers", null);
    }

    private static ToggleButton option(String text) {
        return (ToggleButton) SettingsWindow.current().getScene().getRoot().lookupAll(".toggle-button").stream()
                .filter(n -> text.equals(n.getAccessibleText())).findFirst().orElseThrow();
    }

    @Test
    void whatsappAnimatedStickersCanGoAsGifOrVideo() throws Exception {
        fx(() -> {
            Stage owner = new Stage();
            owner.setOpacity(0);
            owner.show();
            SettingsWindow.show(owner, new MockChatService(), () -> { }, () -> { }, id -> { }, t -> { });
            SettingsWindow.select("Redes");
            return null;
        });
        FxWait.until("the WhatsApp options show", () -> option("Enviar stickers animados de WhatsApp como Sticker") != null);
        assertTrue(fx(() -> option("Enviar stickers animados de WhatsApp como Sticker").isSelected()), "sticker by default");
        fx(() -> { option("Enviar stickers animados de WhatsApp como GIF").fire(); return null; });
        assertEquals("GIF", Settings.get().getString("whatsapp.animatedStickers", null));
        fx(() -> { option("Enviar stickers animados de WhatsApp como Video").fire(); return null; });
        assertEquals("VIDEO", Settings.get().getString("whatsapp.animatedStickers", null));
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }
}

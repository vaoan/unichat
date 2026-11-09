package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Telegram-like side panel: Perfil and Emojis in one panel that switches without closing. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SidePanelUiTest {
    @Test
    void oneSidePanelForProfileAndEmojis() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        MainView[] view = new MainView[1];
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setX(-10000);
            view[0] = new MainView(new MockChatService(), stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view[0], 1200, 800);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view[0].openChatId("!familia:demo");
            return sc;
        });
        FxWait.until("the chat opens", () -> scene.getRoot().lookup(".composer-input") != null);
        FxWait.settled();
        assertFalse(fx(() -> panel(scene) != null), "closed at first");

        fx(() -> { view[0].togglePanel(SidePanel.Tab.PROFILE); return null; });
        assertEquals(SidePanel.Tab.PROFILE, fx(() -> panel(scene).current()));
        assertTrue(fx(() -> panel(scene).lookup(".info-pane") != null), "Perfil shows the chat info");

        fx(() -> { view[0].showPanel(SidePanel.Tab.EMOJIS); return null; });
        assertEquals(SidePanel.Tab.EMOJIS, fx(() -> panel(scene).current()), "switches tab without closing");
        FxWait.until("the scroll pane builds its emojis", () -> panel(scene).lookup(".emoji-button") != null);

        fx(() -> {
            Button smile = (Button) panel(scene).lookupAll(".emoji-button").iterator().next();
            smile.fire();
            return null;
        });
        String typed = fx(() -> ((TextArea) scene.getRoot().lookup(".composer-input")).getText());
        assertFalse(typed.isEmpty(), "the emoji went into the message box");

        fx(() -> { view[0].togglePanel(SidePanel.Tab.EMOJIS); return null; });
        assertFalse(fx(() -> panel(scene) != null), "same button closes it");

        // The composer's one button (the face) opens the media tab used last; Perfil doesn't count.
        fx(() -> { view[0].showPanel(SidePanel.Tab.STICKERS); view[0].showPanel(SidePanel.Tab.PROFILE); return null; });
        fx(() -> { view[0].togglePanel(SidePanel.Tab.PROFILE); return null; });
        assertEquals(SidePanel.Tab.STICKERS, SidePanel.lastMediaTab());
        fx(() -> {
            scene.getRoot().lookupAll(".icon-button").stream()
                    .filter(n -> "Emojis, stickers y GIFs".equals(n.getAccessibleText())).findFirst()
                    .map(Button.class::cast).orElseThrow().fire();
            return null;
        });
        assertEquals(SidePanel.Tab.STICKERS, fx(() -> panel(scene).current()), "opens where it was left");
        assertTrue(fx(() -> scene.getRoot().lookupAll(".gif-button").isEmpty()), "no separate GIF button any more");
    }

    @Test
    void stickersTabShowsFavouritesAndSendsThem() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        MockChatService svc = new MockChatService();
        MainView[] view = new MainView[1];
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setX(-10000);
            view[0] = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view[0], 1200, 800);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view[0].openChatId("!familia:demo");
            // The library persists in the test data dir: make sure it's a favourite, whatever a previous run left.
            if (!view[0].library().isFavorite("mxc://demo/fav")) view[0].library().toggleFavorite(new app.unichat.model.MediaLibrary.Item(
                    app.unichat.model.MediaLibrary.Kind.STICKER, "mxc://demo/fav", "image/png", 160, 160, null));
            return sc;
        });
        FxWait.until("the chat opens", () -> scene.getRoot().lookup(".composer-input") != null);
        fx(() -> { view[0].showPanel(SidePanel.Tab.STICKERS); return null; });
        FxWait.until("the stickers tab lists its tiles", () -> panel(scene).lookup(".sticker-tile") != null);
        int before = fx(() -> svc.chat("!familia:demo").lastPreview().length());
        fx(() -> {
            Node tile = panel(scene).lookup(".sticker-tile");
            assertTrue(tile != null, "the favourite is listed");
            assertEquals("Sticker favorito, enviar", tile.getAccessibleText());
            tile.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            return null;
        });
        FxWait.until("the sticker is sent", () -> "Sticker".equals(svc.chat("!familia:demo").lastPreview()));
        assertEquals("Sticker", fx(() -> svc.chat("!familia:demo").lastPreview()), "sent to the open chat");
        assertTrue(before >= 0);

        // Right-click offers to see it big, and that opens it in its own window.
        fx(() -> {
            Node tile = panel(scene).lookup(".sticker-tile");
            var b = tile.localToScreen(tile.getBoundsInLocal());
            tile.fireEvent(new javafx.scene.input.ContextMenuEvent(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    5, 5, b.getMinX() + 5, b.getMinY() + 5, false, null));
            return null;
        });
        FxWait.until("the context menu shows", () -> javafx.stage.Window.getWindows().stream()
                .anyMatch(w -> w instanceof javafx.scene.control.ContextMenu));
        javafx.scene.control.MenuItem enlarge = fx(() -> javafx.stage.Window.getWindows().stream()
                .filter(w -> w instanceof javafx.scene.control.ContextMenu).map(w -> (javafx.scene.control.ContextMenu) w)
                .flatMap(m -> m.getItems().stream()).filter(i -> "Ver en grande".equals(i.getText())).findFirst().orElse(null));
        assertTrue(enlarge != null, "the menu offers Ver en grande");
        long windows = fx(() -> javafx.stage.Window.getWindows().stream().filter(w -> w instanceof Stage).count());
        fx(() -> { enlarge.fire(); return null; });
        FxWait.until("the sticker's window opens", () -> javafx.stage.Window.getWindows().stream()
                .anyMatch(w -> w instanceof Stage s && "Sticker".equals(s.getTitle())));
        Stage big = fx(() -> javafx.stage.Window.getWindows().stream().filter(w -> w instanceof Stage s && "Sticker".equals(s.getTitle()))
                .map(Stage.class::cast).findFirst().orElse(null));
        assertTrue(big != null && fx(() -> javafx.stage.Window.getWindows().stream().filter(w -> w instanceof Stage).count()) > windows,
                "a window shows the sticker");
        fx(() -> { big.close(); return null; });

        // A network that rejects stickers: shown dimmed, with the reason, and nothing is sent.
        fx(() -> {
            svc.chat("!familia:demo").setFeatures(app.unichat.model.RoomFeatures.parse(
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"id\":\"gm\",\"file\":{\"m.image\":{\"mime_types\":{\"image/*\":2}}}}")));
            view[0].showPanel(SidePanel.Tab.STICKERS);
            return null;
        });
        FxWait.until("the tile tells why it's dimmed",
                () -> panel(scene).lookup(".sticker-tile").getAccessibleText().contains("no acepta stickers"));
        String text = fx(() -> panel(scene).lookup(".sticker-tile").getAccessibleText());
        assertTrue(text.contains("no acepta stickers"), text);
    }

    @Test
    void gifsTabExplainsHowToTurnOnAProvider() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        app.unichat.settings.Settings.get().set("gif.giphy.enabled", null);
        app.unichat.settings.Settings.get().set("gif.klipy.enabled", null);
        MainView[] view = new MainView[1];
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setX(-10000);
            view[0] = new MainView(new MockChatService(), stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view[0], 1200, 800);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view[0].openChatId("!familia:demo");
            return sc;
        });
        FxWait.until("the chat opens", () -> scene.getRoot().lookup(".composer-input") != null);
        fx(() -> { view[0].showPanel(SidePanel.Tab.GIFS); return null; });
        FxWait.until("the GIFs tab explains how to turn on a provider", () -> panel(scene).lookupAll(".label").stream()
                .anyMatch(n -> ((javafx.scene.control.Label) n).getText() != null
                        && ((javafx.scene.control.Label) n).getText().contains("Ajustes → GIFs")));
        String text = fx(() -> panel(scene).lookupAll(".label").stream()
                .map(n -> ((javafx.scene.control.Label) n).getText()).reduce("", (a, b) -> a + "\n" + b));
        assertTrue(text.contains("Ajustes → GIFs"), text);
        assertTrue(fx(() -> panel(scene).lookup(".gif-search") != null), "search box is there");
    }

    private static SidePanel panel(Scene s) {
        for (Node n : s.getRoot().lookupAll("*")) if (n instanceof SidePanel p && p.getScene() != null) return p;
        return null;
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }
}

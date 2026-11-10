package app.unichat.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every emoji of the list shows whole: what JavaFX lays out in pieces (flags, keycaps, joined sequences) is drawn as a
 * picture. Needs the Mac's emoji font, so a real display. {@code -Dunichat.emojiShot=<png>} saves the flags.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
@EnabledOnOs(OS.MAC)
class EmojiPickerUiTest {
    /** The right-click menu of an emoji reads like any other menu: once it took the emoji's size and font (huge). */
    @Test
    void theMenuOfAnEmojiHasTheWindowsFont() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("emoji-menu");
        app.unichat.model.MediaLibrary library = new app.unichat.model.MediaLibrary(dir.resolve("media-library.json"));
        Button b = FxWait.fx(() -> {
            Button btn = EmojiPicker.button("😀", e -> { }, library, t -> { });
            Stage stage = new Stage();
            VBox root = new VBox(btn);
            root.setStyle("-fx-font-size: 13px;");
            Scene scene = new Scene(root, 300, 200);
            scene.getStylesheets().add(EmojiPickerUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            return btn;
        });
        FxWait.settled();
        FxWait.fx(() -> {
            var p = b.localToScreen(5, 5);
            javafx.event.Event.fireEvent(b, new javafx.scene.input.ContextMenuEvent(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    5, 5, p.getX(), p.getY(), false, null));
            return null;
        });
        FxWait.settled();
        javafx.scene.control.ContextMenu menu = FxWait.fx(() -> javafx.stage.Window.getWindows().stream()
                .filter(w -> w instanceof javafx.scene.control.ContextMenu && w.isShowing())
                .map(w -> (javafx.scene.control.ContextMenu) w).findFirst().orElseThrow());
        double size = FxWait.fx(() -> ((javafx.scene.control.Labeled) menu.getSkin().getNode().lookup(".menu-item .label")).getFont().getSize());
        String family = FxWait.fx(() -> ((javafx.scene.control.Labeled) menu.getSkin().getNode().lookup(".menu-item .label")).getFont().getFamily());
        FxWait.fx(() -> { menu.hide(); return null; });
        assertTrue(size < 16, "menu text at the window's size, was " + size);
        assertTrue(!family.contains("Emoji"), "not the emoji font: " + family);
        assertTrue(FxWait.fx(() -> menu.getItems().stream().noneMatch(i -> app.unichat.support.I18n.t("library.enlarge").equals(i.getText()))),
                "no 'see it big' for an emoji");
    }

    @Test
    void flagsAndJoinedEmojiShowAsOnePicture() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        VBox grid = FxWait.fx(() -> {
            VBox g = EmojiPicker.grid(e -> { }, 300);
            Stage stage = new Stage();
            ScrollPane sp = new ScrollPane(g);
            sp.setFitToWidth(true);
            Scene scene = new Scene(sp, 340, 600);
            scene.getStylesheets().add(EmojiPickerUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            return g;
        });
        FxWait.fx(() -> EmojiArt.pictures()).get(30, java.util.concurrent.TimeUnit.SECONDS);
        FxWait.settled();
        List<Button> buttons = FxWait.fx(() -> grid.lookupAll(".emoji-button").stream().map(n -> (Button) n).toList());
        int total = EmojiPicker.CATEGORIES.values().stream().mapToInt(List::size).sum();
        assertEquals(total, buttons.size());
        for (String e : List.of("🇨🇴", "👨‍🦰", "#️⃣", "❤️‍🔥", "🏳️‍🌈")) {
            Button b = FxWait.fx(() -> buttons.stream().filter(x -> x.getAccessibleText().endsWith(" " + e)).findFirst().orElseThrow());
            assertTrue(FxWait.fx(() -> b.isVisible() && b.getGraphic() instanceof ImageView), e + " drawn as a picture");
        }
        long hidden = FxWait.fx(() -> buttons.stream().filter(b -> !b.isVisible()).count());
        assertEquals(0, hidden, "the Mac's font joins all of them");
        String shot = System.getProperty("unichat.emojiShot");
        if (shot != null) {
            // The people: emoji drawn as text beside those drawn as pictures, at the same size.
            Node flags = FxWait.fx(() -> grid.getChildren().get(3));
            var img = FxWait.fx(() -> flags.snapshot(null, null));
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(shot));
        }
    }
}

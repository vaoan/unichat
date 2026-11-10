package app.unichat.ui.emoji;

import app.unichat.ui.FxWait;
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
        FxWait.fx(() -> { b.getScene().getWindow().hide(); return null; });
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
        FxWait.fx(() -> EmojiArt.pictures()).get(); // JUnit's own timeout catches one that never ends
        EmojiGrid grid = FxWait.fx(() -> {
            EmojiGrid g = new EmojiGrid(null, EmojiGrid.Mode.PLAIN, e -> { }, t -> { });
            Stage stage = new Stage();
            Scene scene = new Scene(g, 340, 600);
            scene.getStylesheets().add(EmojiPickerUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            return g;
        });
        // The list itself: the box measuring a button is styled as the list too.
        javafx.scene.control.ListView<EmojiRows.Row> list = FxWait.fx(() -> grid.lookupAll(".emoji-list").stream()
                .filter(n -> n instanceof javafx.scene.control.ListView).map(n -> (javafx.scene.control.ListView<EmojiRows.Row>) n)
                .findFirst().orElseThrow());
        FxWait.until("the rows are laid out", () -> !list.getItems().isEmpty());
        List<String> listed = FxWait.fx(() -> list.getItems().stream().filter(r -> r instanceof EmojiRows.Emojis)
                .flatMap(r -> ((EmojiRows.Emojis) r).emoji().stream()).toList());
        int total = EmojiPicker.CATEGORIES.values().stream().mapToInt(List::size).sum();
        assertEquals(total, listed.size(), "the Mac's font joins all of them: none left out");
        for (String e : List.of("🇨🇴", "👨‍🦰", "#️⃣", "❤️‍🔥", "🏳️‍🌈", "🫠")) {
            int row = FxWait.fx(() -> {
                for (int i = 0; i < list.getItems().size(); i++) {
                    if (list.getItems().get(i) instanceof EmojiRows.Emojis r && r.emoji().contains(e)) return i;
                }
                return -1;
            });
            FxWait.fx(() -> { list.scrollTo(row); return null; });
            FxWait.until(e + " on screen", () -> button(grid, e) != null);
            Button b = FxWait.fx(() -> button(grid, e));
            boolean picture = !e.equals("🫠");
            assertEquals(picture, FxWait.fx(() -> b.getGraphic() instanceof ImageView), e + (picture ? " drawn as a picture" : " drawn as text"));
        }
        String shot = System.getProperty("unichat.emojiShot");
        if (shot != null) {
            var img = FxWait.fx(() -> grid.snapshot(null, null));
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(shot));
        }
        FxWait.fx(() -> { grid.getScene().getWindow().hide(); return null; });
    }

    /** Typing in the search keeps what's called that, in the language in use; Enter takes the first. */
    @Test
    void searchingFindsByNameAndEnterPicksTheFirst() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        java.util.concurrent.CompletableFuture<String> picked = new java.util.concurrent.CompletableFuture<>();
        EmojiGrid grid = FxWait.fx(() -> {
            EmojiGrid g = new EmojiGrid(null, EmojiGrid.Mode.PLAIN, picked::complete, t -> { });
            Stage stage = new Stage();
            stage.setScene(new Scene(g, 340, 500));
            stage.show();
            return g;
        });
        javafx.scene.control.ListView<EmojiRows.Row> list = FxWait.fx(() -> grid.lookupAll(".emoji-list").stream()
                .filter(n -> n instanceof javafx.scene.control.ListView).map(n -> (javafx.scene.control.ListView<EmojiRows.Row>) n)
                .findFirst().orElseThrow());
        FxWait.until("laid out", () -> !list.getItems().isEmpty());
        FxWait.fx(() -> { grid.searchField().setText("cara derrit"); return null; });
        List<EmojiRows.Row> rows = FxWait.fx(() -> List.copyOf(list.getItems()));
        assertEquals(new EmojiRows.Title("Resultados"), rows.get(0));
        assertEquals(List.of("🫠"), rows.stream().filter(r -> r instanceof EmojiRows.Emojis).flatMap(r -> ((EmojiRows.Emojis) r).emoji().stream()).toList());
        FxWait.fx(() -> {
            javafx.event.Event.fireEvent(grid.searchField(), new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                    "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            return null;
        });
        assertEquals("🫠", picked.get());
        FxWait.fx(() -> { grid.searchField().setText("zzqx"); return null; });
        assertEquals(new EmojiRows.Hint("Ningún emoji se llama así."), FxWait.fx(() -> list.getItems().get(1)));
        FxWait.fx(() -> { grid.getScene().getWindow().hide(); return null; });
    }

    private static Button button(Node in, String e) {
        return in.lookupAll(".emoji-button").stream().map(n -> (Button) n)
                .filter(b -> b.isVisible() && e.equals(b.getProperties().get("emoji"))).findFirst().orElse(null);
    }
}

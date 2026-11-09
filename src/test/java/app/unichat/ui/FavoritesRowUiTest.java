package app.unichat.ui;

import app.unichat.service.MockChatService;
import app.unichat.settings.Settings;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The favorites: tiles of one width, each name inside its tile, centered, cut with "…" when long; at any text size. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class FavoritesRowUiTest {
    private MockChatService svc;
    private Stage stage;
    private double scale;

    @BeforeAll
    static void toolkit() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
    }

    @AfterEach
    void close() throws Exception {
        Settings.get().setFontScale(scale);
        svc.stop();
        FxWait.fx(() -> {
            stage.close();
            return null;
        });
    }

    private Scene open(double fontScale) throws Exception {
        scale = Settings.get().fontScale();
        Settings.get().setFontScale(fontScale);
        svc = new MockChatService();
        svc.chat("!gaming:demo").setTitle("La Fiera de Cali y sus amigos");
        svc.setFavorite("!gaming:demo", true).get();
        Scene scene = FxWait.fx(() -> {
            stage = new Stage();
            MainView view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 760);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        FxWait.until("three favorites", () -> scene.getRoot().lookupAll(".favorite-tile").size() >= 3);
        FxWait.settled();
        return scene;
    }

    private void check(Scene scene) throws Exception {
        String shot = System.getProperty("unichat.favoritesShot");
        if (shot != null) FxWait.fx(() -> {
            Node row = scene.getRoot().lookup(".favorites");
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(row.snapshot(null, null), null), "png",
                    new java.io.File(shot + "-" + Math.round(Settings.get().fontScale() * 100) + ".png"));
            return null;
        });
        List<String> problems = FxWait.fx(() -> {
            List<String> out = new java.util.ArrayList<>();
            List<Node> tiles = scene.getRoot().lookupAll(".favorite-tile").stream().filter(Node::isVisible).toList();
            double width = tiles.get(0).getLayoutBounds().getWidth();
            for (Node t : tiles) {
                Label name = (Label) t.lookup(".favorite-name");
                Bounds tile = t.localToScene(t.getLayoutBounds());
                Node shown = name.lookup(".text");
                Bounds text = shown.localToScene(shown.getLayoutBounds());
                String said = ((javafx.scene.text.Text) shown).getText();
                if (Math.abs(t.getLayoutBounds().getWidth() - width) > 0.5) out.add(name.getText() + ": another width");
                if (text.getMinX() < tile.getMinX() - 0.5 || text.getMaxX() > tile.getMaxX() + 0.5) out.add(said + ": outside its tile");
                double off = (text.getMinX() + text.getMaxX()) / 2 - (tile.getMinX() + tile.getMaxX()) / 2;
                if (Math.abs(off) > 2) out.add(said + ": not centered (" + Math.round(off) + " px)");
                if (name.getText().startsWith("La Fiera") && !said.endsWith("…")) out.add(said + ": a long name without …");
            }
            return out;
        });
        assertEquals(List.of(), problems);
    }

    @Test
    void aFavoriteFolderShowsAmongTheFavoritesAndOpensTheFolder() throws Exception {
        Scene scene = open(1.0);
        String id = svc.createFolder("Trabajo");
        svc.setInFolder(id, svc.chat("!devs:demo"), true);
        svc.setFolderFavorite(id, true);
        FxWait.until("the folder among the favorites", () -> scene.getRoot().lookup(".favorite-tile.favorite-folder") != null);
        FxWait.settled(); // drawn, to be measured
        Node tile = FxWait.fx(() -> scene.getRoot().lookup(".favorite-tile.favorite-folder"));
        assertEquals("Trabajo", FxWait.fx(() -> ((Label) tile.lookup(".favorite-name")).getText()));
        assertTrue(FxWait.fx(() -> tile.getAccessibleText().contains("Trabajo")));
        check(scene); // like the chats' tiles
        FxWait.fx(() -> {
            javafx.event.Event.fireEvent(tile, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            return null;
        });
        FxWait.until("the folder open", () -> scene.getRoot().lookupAll(".folder-title").stream()
                .anyMatch(n -> n.isVisible() && "Trabajo".equals(((Label) n).getText())));
        FxWait.fx(() -> { scene.getRoot().lookupAll("*").stream().filter(MainView.class::isInstance).map(MainView.class::cast)
                .findFirst().orElseThrow().openChatId("!familia:demo"); return null; }); // back to the inbox
        svc.setFolderFavorite(id, false);
        FxWait.until("no longer among the favorites", () -> scene.getRoot().lookup(".favorite-tile.favorite-folder") == null);
    }

    /** The favorites' row: one line, never wider than the column; true when a "+N" tile stands for the rest. */
    private List<String> oneRowProblems(Scene scene) throws Exception {
        String shot = System.getProperty("unichat.favoritesShot");
        if (shot != null) FxWait.fx(() -> {
            Node row = scene.getRoot().lookup(".favorites").getParent();
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(row.snapshot(null, null), null), "png",
                    new java.io.File(shot + "-row-" + scene.getRoot().lookupAll(".favorite-tile").size() + ".png"));
            return null;
        });
        return FxWait.fx(() -> {
            List<String> out = new java.util.ArrayList<>();
            Node row = scene.getRoot().lookup(".favorites");
            Bounds r = row.localToScene(row.getLayoutBounds());
            Double top = null;
            for (Node t : row.lookupAll(".favorite-tile")) {
                if (!t.isVisible()) continue;
                Bounds b = t.localToScene(t.getLayoutBounds());
                if (top == null) top = b.getMinY();
                if (Math.abs(b.getMinY() - top) > 0.5) out.add("a tile on another line");
                if (b.getMaxX() > r.getMaxX() + 0.5) out.add("a tile past the column");
            }
            return out;
        });
    }

    @Test
    void fourFavoritesStayOnOneLineWithLargeText() throws Exception {
        Scene scene = open(1.6);
        svc.setFavorite("!devs:demo", true).get();
        FxWait.fx(() -> { Theme.applyAll(); stage.setWidth(960); return null; });
        FxWait.until("four favorites", () -> scene.getRoot().lookupAll(".favorite-tile").size() >= 4);
        FxWait.settled();
        assertEquals(List.of(), oneRowProblems(scene));
        assertEquals(4L, (long) FxWait.fx(() -> scene.getRoot().lookupAll(".favorite-tile").stream()
                .filter(Node::isVisible).filter(n -> !n.getStyleClass().contains("favorite-more")).count()), "all four, narrower");
    }

    @Test
    void moreThanFitLeaveATileForTheRestThatOpensTheFavorites() throws Exception {
        Scene scene = open(1.6);
        for (String id : List.of("!devs:demo", "!familia:demo", "!sofia:demo", "!valen:demo", "!notas:demo", "!carlos:demo")) {
            svc.setFavorite(id, true).get();
        }
        FxWait.fx(() -> { Theme.applyAll(); stage.setWidth(900); return null; });
        FxWait.until("the tile for the rest", () -> scene.getRoot().lookup(".favorite-more") != null
                && scene.getRoot().lookup(".favorite-more").isVisible());
        FxWait.settled();
        assertEquals(List.of(), oneRowProblems(scene));
        long all = FxWait.fx(() -> svc.chats().stream().filter(c -> c.favorite() && !c.archived() && !c.personalSpace() && !c.bridgeBot()).count());
        long shown = FxWait.fx(() -> scene.getRoot().lookupAll(".favorite-tile").stream()
                .filter(Node::isVisible).filter(n -> !n.getStyleClass().contains("favorite-more")).count());
        Node more = FxWait.fx(() -> scene.getRoot().lookup(".favorite-more"));
        assertEquals("+" + (all - shown), FxWait.fx(() -> ((Label) more.lookup(".favorite-more-count")).getText()));
        FxWait.fx(() -> {
            javafx.event.Event.fireEvent(more, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            return null;
        });
        FxWait.until("the Favorites view", () -> scene.getRoot().lookupAll(".pane-title").stream()
                .anyMatch(n -> n instanceof Label l && l.getText().equals(app.unichat.support.I18n.t("chatList.title.favorites"))));
    }

    @Test
    void namesFitTheirTiles() throws Exception {
        check(open(1.0));
    }

    @Test
    void namesFitTheirTilesWithLargeText() throws Exception {
        Scene scene = open(1.6);
        FxWait.fx(() -> { Theme.applyAll(); return null; });
        FxWait.settled();
        check(scene);
    }
}

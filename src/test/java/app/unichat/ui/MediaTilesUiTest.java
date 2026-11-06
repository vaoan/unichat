package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Changing chats doesn't load the stickers again. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class MediaTilesUiTest {
    @TempDir Path dir;
    private final AtomicInteger asked = new AtomicInteger();
    private MediaLibrary library;
    private StickerGrid grid;
    private Stage stage;

    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
    }

    private MediaLibrary.Item sticker(String name) throws Exception {
        Path f = dir.resolve(name + ".png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", f.toFile());
        return new MediaLibrary.Item(MediaLibrary.Kind.STICKER, f.toUri().toString(), "image/png", 8, 8, null);
    }

    @BeforeEach
    void open() throws Exception {
        library = new MediaLibrary(dir.resolve("lib.json"));
        library.toggleFavorite(sticker("uno"));
        library.toggleFavorite(sticker("dos"));
        MockChatService mock = new MockChatService();
        app.unichat.service.ChatService svc = (app.unichat.service.ChatService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{app.unichat.service.ChatService.class}, (p, m, args) -> {
                    if (m.getName().equals("media")) asked.incrementAndGet();
                    return m.invoke(mock, args);
                });
        fx(() -> {
            grid = new StickerGrid(svc, library, () -> null, t -> { });
            stage = new Stage();
            stage.setScene(new Scene(grid, 400, 400));
            stage.show();
            grid.refresh();
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> { stage.close(); return null; });
    }

    private List<Node> tiles() throws Exception {
        return fx(() -> ((VBox) grid.getContent()).getChildren().stream().filter(FlowPane.class::isInstance).findFirst()
                .map(g -> List.copyOf(((FlowPane) g).getChildren())).orElseThrow());
    }

    @Test
    void refreshingKeepsTheTilesAndAsksForNothing() throws Exception {
        List<Node> before = tiles();
        int loads = asked.get();
        fx(() -> { grid.refresh(); return null; });
        assertEquals(loads, asked.get(), "nothing asked again");
        assertSame(before.get(0), tiles().get(0));
        assertSame(before.get(1), tiles().get(1));
    }

    @Test
    void aNewFavouriteOnlyLoadsItself() throws Exception {
        List<Node> before = tiles();
        int loads = asked.get();
        MediaLibrary.Item tres = sticker("tres");
        fx(() -> { library.toggleFavorite(tres); return null; });
        long end = System.currentTimeMillis() + 5_000;
        while (tiles().size() < 3 && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals(3, tiles().size());
        assertTrue(tiles().containsAll(before));
        assertTrue(asked.get() - loads <= 2, "thumbnail and original of that one: " + (asked.get() - loads));
    }

    @Test
    void whatsDecodedShowsAtOnce() throws Exception {
        MediaLibrary.Item item = sticker("cuatro");
        Path file = Path.of(java.net.URI.create(item.url()));
        assertTrue(Files.exists(file));
        AnimatedImage.Frames frames = AnimatedImage.decodePlain(file, 100, 100);
        MockChatService svc = new MockChatService();
        ImageView shown = fx(() -> {
            ImageView v = new ImageView();
            MessageMedia.loadMedia(svc, v, MediaItems.message(item), 100, 100);
            assertSame(frames.images().get(0), v.getImage(), "on this very call");
            return v;
        });
        assertNotNull(shown);
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        if (Platform.isFxApplicationThread()) return c.call();
        FutureTask<T> t = new FutureTask<>(c);
        Platform.runLater(t);
        return t.get(20, TimeUnit.SECONDS);
    }
}

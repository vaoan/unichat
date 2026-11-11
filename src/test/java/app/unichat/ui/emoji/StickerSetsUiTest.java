package app.unichat.ui.emoji;

import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.RoomFeatures;
import app.unichat.model.StickerPack;
import app.unichat.service.ChatService;
import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telegram's sticker sets in the Stickers tab: the person's own sets, each opened on demand (fetched once), its stickers
 * sent like a favourite; and a set opened from one of its stickers, though it isn't one of theirs.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class StickerSetsUiTest {
    private static final String GATITOS = "https://t.me/addstickers/gatitos";
    private static final String PERROS = "https://t.me/addstickers/perros_2";

    @TempDir Path dir;
    private final AtomicInteger fetched = new AtomicInteger();
    private final List<MediaLibrary.Item> sent = new CopyOnWriteArrayList<>();
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
        up.get();
        Platform.setImplicitExit(false);
    }

    private StickerPack pack(String title, String url, String... names) throws Exception {
        List<MediaLibrary.Item> items = new java.util.ArrayList<>();
        for (String n : names) {
            Path f = dir.resolve(n + ".png");
            ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", f.toFile());
            items.add(new MediaLibrary.Item(MediaLibrary.Kind.STICKER, f.toUri().toString(), "image/png", 8, 8, null));
        }
        return new StickerPack(title, url, items);
    }

    @BeforeEach
    void open() throws Exception {
        StickerPack gatitos = pack("Gatitos", GATITOS, "g1", "g2");
        StickerPack perros = pack("Perros_y_gatos", PERROS, "p1");
        MockChatService mock = new MockChatService();
        Chat chat = mock.chats().stream().filter(c -> c.features().accepts(RoomFeatures.STICKER)).findFirst().orElseThrow();
        ChatService svc = (ChatService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ChatService.class}, (p, m, args) -> switch (m.getName()) {
                    case "stickerPacks" -> CompletableFuture.completedFuture(List.of(new StickerPack.Ref("Gatitos", GATITOS)));
                    case "stickerPack" -> {
                        fetched.incrementAndGet();
                        yield CompletableFuture.completedFuture(GATITOS.equals(args[0]) ? gatitos : perros);
                    }
                    case "sendSticker" -> {
                        sent.add((MediaLibrary.Item) args[1]);
                        yield CompletableFuture.completedFuture(null);
                    }
                    default -> m.invoke(mock, args);
                });
        MediaLibrary library = new MediaLibrary(dir.resolve("lib.json"));
        FxWait.fx(() -> {
            grid = new StickerGrid(svc, library, () -> chat, () -> null, t -> { });
            stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(grid, 400, 600));
            stage.show();
            grid.refresh();
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        FxWait.fx(() -> { stage.close(); return null; });
    }

    private List<Node> content() throws Exception {
        return FxWait.fx(() -> List.copyOf(((VBox) grid.getContent()).getChildren()));
    }

    private Button set(String title) throws Exception {
        return (Button) content().stream().filter(n -> n instanceof Button b && b.getText().equals(title)).findFirst().orElse(null);
    }

    /** The stickers shown under {@code title}'s header, if it's open. */
    private List<Node> stickersOf(String title) throws Exception {
        List<Node> c = content();
        int i = c.indexOf(set(title));
        return i >= 0 && i + 1 < c.size() && c.get(i + 1) instanceof FlowPane f ? FxWait.fx(() -> List.copyOf(f.getChildren())) : List.of();
    }

    @Test
    void yourSetsOpenOnDemandAndTheirStickersAreSent() throws Exception {
        FxWait.until("your set listed", () -> set("Gatitos") != null);
        assertEquals(0, fetched.get(), "closed: nothing fetched");
        assertTrue(stickersOf("Gatitos").isEmpty());
        FxWait.fx(() -> { set("Gatitos").fire(); return null; });
        FxWait.until("its stickers", () -> stickersOf("Gatitos").size() == 2);
        Node first = stickersOf("Gatitos").get(0);
        assertTrue(FxWait.fx(first::getAccessibleText).contains("Gatitos"), FxWait.fx(first::getAccessibleText));
        FxWait.fx(() -> {
            Event.fireEvent(first, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        FxWait.until("sent", () -> sent.size() == 1);
        assertTrue(sent.get(0).url().endsWith("g1.png"));
        FxWait.fx(() -> { set("Gatitos").fire(); return null; });
        FxWait.until("closed again", () -> stickersOf("Gatitos").isEmpty());
        FxWait.fx(() -> { set("Gatitos").fire(); return null; });
        FxWait.until("open again", () -> stickersOf("Gatitos").size() == 2);
        assertEquals(1, fetched.get(), "a set is fetched once");
    }

    @Test
    void aSetOpenedFromOneOfItsStickersShowsThoughItIsntYours() throws Exception {
        FxWait.fx(() -> { grid.showPack(PERROS); return null; });
        FxWait.until("that set, open, under its own name", () -> stickersOf("Perros_y_gatos").size() == 1);
        Button head = set("Perros_y_gatos");
        assertTrue(!FxWait.fx(head::isMnemonicParsing), "its underscores are shown, not taken as a shortcut");
        assertTrue(set("Gatitos") != null, "yours are still there");
    }
}

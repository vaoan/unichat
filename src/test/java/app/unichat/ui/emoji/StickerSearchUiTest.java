package app.unichat.ui.emoji;

import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.RoomFeatures;
import app.unichat.model.StickerPack;
import app.unichat.service.ChatService;
import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
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
import app.unichat.Present;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Searching stickers in the Stickers tab, as Telegram does: a word finds the stickers of the emoji it names, an emoji its
 * own, a set's name that set; in your favourites, recents and every set of yours, brought for it though none was open.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class StickerSearchUiTest {
    private static final String GATITOS = "https://t.me/addstickers/gatitos";
    private static final String PERROS = "https://t.me/addstickers/perros";
    /** How each set answers when asked, by its link: brought at once unless a test says otherwise. */
    private final Map<String, Supplier<CompletableFuture<StickerPack>>> answers = new ConcurrentHashMap<>();

    @TempDir Path dir;
    private final AtomicInteger fetched = new AtomicInteger();
    private final List<MediaLibrary.Item> sent = new CopyOnWriteArrayList<>();
    private MediaLibrary library;
    @SuppressWarnings("NullAway.Init") // the @BeforeEach sets it on the FX thread
    private StickerGrid grid;
    @SuppressWarnings("NullAway.Init") // the @BeforeEach sets it on the FX thread
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

    private MediaLibrary.Item sticker(String name, String emoji) throws IOException {
        Path f = dir.resolve(name + ".png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", f.toFile());
        ObjectNode info = JsonNodeFactory.instance.objectNode();
        info.putObject("fi.mau.bridged_sticker").put("emoji", emoji);
        return new MediaLibrary.Item(MediaLibrary.Kind.STICKER, f.toUri().toString(), "image/png", 8, 8, info);
    }

    @BeforeEach
    void open() throws Exception {
        StickerPack gatitos = new StickerPack("Gatitos felices", GATITOS,
                List.of(sticker("gato", "🐱"), sticker("risa", "😂"), sticker("corazon", "❤️")));
        StickerPack perros = new StickerPack("Perros", PERROS, List.of(sticker("perro", "🐶")));
        answers.put(GATITOS, () -> CompletableFuture.completedFuture(gatitos));
        answers.put(PERROS, () -> CompletableFuture.completedFuture(perros));
        MockChatService mock = new MockChatService();
        Chat chat = mock.chats().stream().filter(c -> c.features().accepts(RoomFeatures.STICKER)).findFirst().orElseThrow();
        ChatService svc = (ChatService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ChatService.class}, (p, m, args) -> switch (m.getName()) { // NOPMD: UseProperClassLoader, the proxy is made where its interface was loaded
                    case "stickerPacks" -> CompletableFuture.completedFuture(List.of(
                            new StickerPack.Ref("Gatitos felices", GATITOS), new StickerPack.Ref("Perros", PERROS)));
                    case "stickerPack" -> {
                        fetched.incrementAndGet();
                        yield Present.of(answers.get((String) args[0])).get();
                    }
                    case "sendSticker" -> {
                        sent.add((MediaLibrary.Item) args[1]);
                        yield CompletableFuture.completedFuture(null);
                    }
                    default -> m.invoke(mock, args);
                });
        library = new MediaLibrary(dir.resolve("lib.json"));
        library.toggleFavorite(sticker("favorito", "😂"));
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
        FxWait.onFx(stage::close);
    }

    private TextField search() throws InterruptedException {
        return FxWait.fx(() -> (TextField) grid.lookup(".emoji-search"));
    }

    private void type(String text) throws InterruptedException {
        FxWait.onFx(() -> search().setText(text));
        FxWait.settled();
    }

    /** The texts and the stickers shown under the search, in order: a label's text, or "sticker:<file>". */
    private List<String> shown() throws InterruptedException {
        return FxWait.fx(() -> {
            List<String> out = new ArrayList<>();
            for (Node n : ((VBox) grid.getContent()).getChildren()) {
                if (n instanceof Label l) out.add(l.getText());
                if (n instanceof javafx.scene.layout.HBox h && h.getChildren().getFirst() instanceof Label l) out.add(l.getText()); // a title with its button
                if (n instanceof FlowPane f) for (Node t : f.getChildren()) out.add("sticker:" + t.getAccessibleText());
            }
            return out;
        });
    }

    private List<Node> tiles() throws InterruptedException {
        return FxWait.fx(() -> {
            List<Node> out = new ArrayList<>();
            for (Node n : ((VBox) grid.getContent()).getChildren()) if (n instanceof FlowPane f) out.addAll(f.getChildren());
            return out;
        });
    }

    /** Enter on a tile, as the keyboard sends one. */
    private static void send(Node tile) throws InterruptedException {
        FxWait.fx(() -> {
            Event.fireEvent(tile, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
    }

    @Test
    void aWordFindsTheStickersOfTheEmojiItNamesInSetsNotOpened() throws Exception {
        type("gato");
        FxWait.until("both sets brought, none was open", () -> fetched.get() == 2);
        FxWait.until("its cat found", () -> tiles().size() == 1);
        assertTrue(FxWait.fx(() -> tiles().getFirst().getAccessibleText()).contains("🐱"), "a screen reader says which sticker");
        assertTrue(shown().contains("Encontrados"), shown().toString());
        send(tiles().getFirst());
        FxWait.until("sent", () -> sent.size() == 1);
        assertTrue(sent.getFirst().url().endsWith("gato.png"), "the one found: " + sent.getFirst().url());
    }

    @Test
    void anEmojiFindsItsOwnFavouritesFirstEachOnce() throws Exception {
        type("😂");
        FxWait.until("the favourite and the set's", () -> tiles().size() == 2);
        for (Node t : tiles()) send(t);
        FxWait.until("both sent", () -> sent.size() == 2);
        assertEquals(List.of("favorito.png", "risa.png"), sent.stream().map(i -> i.url().substring(i.url().lastIndexOf('/') + 1)).toList(),
                "favourites first");
        type("risa");
        assertEquals(2, tiles().size(), "the word finds the same emoji's");
        type("❤");
        assertEquals(1, tiles().size(), "a heart without its variation mark is the same heart");
    }

    @Test
    void setsAreBroughtOneAfterAnother() throws Exception {
        CompletableFuture<StickerPack> slow = new CompletableFuture<>();
        StickerPack gatitos = Present.of(answers.get(GATITOS)).get().get();
        answers.put(GATITOS, () -> slow);
        type("🐶"); // not "perro", which names the set "Perros" too
        FxWait.until("the first asked", () -> fetched.get() == 1);
        FxWait.settled();
        assertEquals(1, fetched.get(), "the next waits: each set is Telegram asked sticker by sticker");
        slow.complete(gatitos);
        FxWait.until("then the next, and its dog", () -> fetched.get() == 2 && tiles().size() == 1);
    }

    @Test
    void aSetThatFailedIsAskedAgainAfterAWhile() throws Exception {
        StickerPack gatitos = Present.of(answers.get(GATITOS)).get().get();
        answers.put(GATITOS, () -> CompletableFuture.failedFuture(new IllegalStateException("the bridge isn't ready")));
        long[] now = {1_000_000};
        FxWait.onFx(() -> grid.sets.clock = () -> now[0]);
        type("gato");
        FxWait.until("both asked, the cats' failed", () -> fetched.get() == 2 && shown().stream().anyMatch(s -> s.contains("«gato»")));
        answers.put(GATITOS, () -> CompletableFuture.completedFuture(gatitos));
        type("gat");
        assertEquals(2, fetched.get(), "not again at once: a failed set isn't asked at every key");
        now[0] += 61_000;
        type("gato");
        FxWait.until("a minute later it's asked again, and found", () -> fetched.get() == 3 && tiles().size() == 1);
    }

    @Test
    void aSearchShowsAtMostAFewScreens() throws Exception {
        List<MediaLibrary.Item> many = new ArrayList<>();
        for (int i = 0; i < StickerGrid.MOST_FOUND + 10; i++) many.add(sticker("perro" + i, "🐶"));
        answers.put(PERROS, () -> CompletableFuture.completedFuture(new StickerPack("Perros", PERROS, many)));
        type("🐶");
        FxWait.until("the first ones, and how many there are", () -> tiles().size() == StickerGrid.MOST_FOUND
                && shown().contains("Se muestran 60 de 70: escribe más para afinar"));
    }

    @Test
    void aSetsNameFindsTheSet() throws Exception {
        type("felices");
        FxWait.until("the set by its name", () -> shown().contains("Gatitos felices") && tiles().size() == 3);
    }

    @Test
    void nothingFoundSaysSoAndEmptyingTheSearchGivesTheTabBack() throws Exception {
        type("zzzz");
        FxWait.until("said", () -> shown().stream().anyMatch(s -> s.contains("«zzzz»")));
        assertTrue(tiles().isEmpty());
        TextField box = search();
        type("");
        assertTrue(shown().contains("Favoritos"), "the tab as it was: " + shown());
        assertSame(box, search(), "the search box itself stays (and keeps the focus while typing)");
    }
}

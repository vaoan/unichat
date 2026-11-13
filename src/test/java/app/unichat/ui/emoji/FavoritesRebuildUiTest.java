package app.unichat.ui.emoji;

import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.RoomFeatures;
import app.unichat.model.StickerPack;
import app.unichat.service.ChatService;
import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Stickers tab's button beside "Favoritos" brings each favourite from its set as it is now, and says how many. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class FavoritesRebuildUiTest {
    private static final String SET = "https://t.me/addstickers/FluxAnimation";
    @TempDir Path dir;
    private final List<String> toasts = new CopyOnWriteArrayList<>();
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

    private static MediaLibrary.Item sticker(String url, String mime) throws Exception {
        JsonNode info = new ObjectMapper().readTree("{\"mimetype\":\"" + mime
                + "\",\"fi.mau.bridged_sticker\":{\"network\":\"telegram\",\"id\":\"5\",\"pack_url\":\"" + SET + "\"}}");
        return new MediaLibrary.Item(MediaLibrary.Kind.STICKER, url, mime, 256, 256, info);
    }

    @AfterEach
    void close() throws Exception {
        if (stage != null) FxWait.fx(() -> { stage.close(); return null; });
    }

    @Test
    void theButtonBesideFavouritesRebuildsThemFromTheirSets() throws Exception {
        MediaLibrary.Item now = sticker("mxc://media-telegram.localhost/new", "video/webm");
        MockChatService mock = new MockChatService();
        Chat chat = mock.chats().stream().filter(c -> c.features().accepts(RoomFeatures.STICKER)).findFirst().orElseThrow();
        ChatService svc = (ChatService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ChatService.class}, (p, m, args) -> switch (m.getName()) {
                    case "freshStickerPack" -> CompletableFuture.completedFuture(new StickerPack("Flux", SET, List.of(now)));
                    case "stickerPacks" -> CompletableFuture.completedFuture(List.of());
                    default -> m.invoke(mock, args);
                });
        MediaLibrary library = new MediaLibrary(dir.resolve("lib.json"));
        library.toggleFavorite(sticker("mxc://media-telegram.localhost/old", "image/gif"));
        StickerGrid grid = FxWait.fx(() -> {
            StickerGrid g = new StickerGrid(svc, library, () -> chat, () -> null, toasts::add);
            stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(g, 400, 600));
            stage.show();
            g.refresh();
            return g;
        });

        Button rebuild = FxWait.fx(() -> (Button) grid.lookupAll(".button").stream()
                .filter(n -> "Actualizar los favoritos desde sus sets".equals(((Node) n).getAccessibleText())).findFirst().orElseThrow());
        FxWait.fx(() -> { rebuild.fire(); return null; });

        FxWait.until("rebuilt", () -> toasts.contains("1 favorito actualizado"));
        assertEquals(List.of(now.url()), library.favorites(MediaLibrary.Kind.STICKER).stream().map(MediaLibrary.Item::url).toList());
    }
}

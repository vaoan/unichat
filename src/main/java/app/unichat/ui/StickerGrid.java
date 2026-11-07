package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.RoomFeatures;
import app.unichat.service.ChatService;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Stickers tab of the side panel: your favourite and recent stickers (shared by every chat and network). A sticker
 * goes to the open chat; if its network doesn't take stickers the grid says so and nothing is sent.
 */
final class StickerGrid extends ScrollPane {
    private static final int TILE = 76;

    private final Supplier<String> takeReply;
    private final ChatService svc;
    private final MediaLibrary library;
    private final Supplier<Chat> chat;
    private final Consumer<String> toast;
    private final VBox content = new VBox(10);
    private final TileCache tiles = new TileCache();

    StickerGrid(ChatService svc, MediaLibrary library, Supplier<Chat> chat, Supplier<String> takeReply, Consumer<String> toast) {
        this.takeReply = takeReply;
        this.svc = svc;
        this.library = library;
        this.chat = chat;
        this.toast = toast;
        setUp(this, content, library, this::refresh);
    }

    /** The look of a media tab; a favourite added or removed anywhere (chat, Favoritos tab) shows on it at once. */
    static void setUp(javafx.scene.control.ScrollPane pane, javafx.scene.layout.Region content, MediaLibrary library, Runnable refresh) {
        content.getStyleClass().add("sticker-grid");
        pane.setContent(content);
        pane.setFitToWidth(true);
        pane.getStyleClass().add("side-panel-scroll");
        library.addListener(() -> Platform.runLater(() -> {
            if (pane.getScene() != null) refresh.run();
        }));
    }

    /** Rebuilds the grid (opening the tab, after sending, after a favourite changes). */
    void refresh() {
        tiles.next();
        content.getChildren().clear();
        Chat c = chat.get();
        boolean accepted = c == null || c.features().accepts(RoomFeatures.STICKER);
        if (!accepted) content.getChildren().add(help("Esta red no acepta stickers."));
        section("Favoritos", library.favorites(MediaLibrary.Kind.STICKER), "Sticker favorito", accepted);
        section("Recientes", library.recents(MediaLibrary.Kind.STICKER), "Sticker reciente", accepted);
        if (library.favorites(MediaLibrary.Kind.STICKER).isEmpty() && library.recents(MediaLibrary.Kind.STICKER).isEmpty()) {
            content.getChildren().add(help("Aún no tienes stickers. Los que te manden aparecerán aquí; guárdalos con clic "
                    + "derecho → Agregar a favoritos, aquí o en el chat."));
        }
    }

    private void section(String title, List<MediaLibrary.Item> items, String what, boolean accepted) {
        if (items.isEmpty()) return;
        Label t = new Label(title);
        t.getStyleClass().add("emoji-category");
        FlowPane grid = new FlowPane(6, 6);
        for (MediaLibrary.Item i : items) grid.getChildren().add(tiles.get(what + accepted + i.url(), () -> tile(i, what, accepted)));
        content.getChildren().addAll(t, grid);
    }

    boolean accepts() {
        Chat c = chat.get();
        return c == null || c.features().accepts(RoomFeatures.STICKER);
    }

    /** A sticker tile for the open chat (also used by the Favoritos tab). */
    Node tile(MediaLibrary.Item item, String what) {
        Chat c = chat.get();
        return tile(item, what, c == null || c.features().accepts(RoomFeatures.STICKER));
    }

    private Node tile(MediaLibrary.Item item, String what, boolean accepted) {
        ImageView view = new ImageView();
        view.setFitWidth(TILE);
        view.setFitHeight(TILE);
        view.setPreserveRatio(true);
        var loading = MessageMedia.loadMedia(svc, view, MediaItems.message(item), TILE * 2, TILE * 2);
        StackPane tile = new StackPane(view);
        tile.getStyleClass().add("sticker-tile");
        tile.setMinSize(TILE, TILE);
        tile.setMaxSize(TILE, TILE);
        tile.setFocusTraversable(true);
        tile.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        tile.setAccessibleText(what + (accepted ? ", enviar" : ", esta red no acepta stickers"));
        tile.setOpacity(accepted ? 1 : 0.4);
        tile.setCursor(accepted ? Cursor.HAND : Cursor.DEFAULT);
        Runnable send = () -> send(item, accepted);
        tile.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) send.run();
        });
        LibraryMenu.attach(tile, library, item, accepted ? send : null, toast);
        MediaItems.markIfLost(loading, tile);
        tile.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                e.consume();
                send.run();
            }
        });
        return tile;
    }

    private void send(MediaLibrary.Item item, boolean accepted) {
        Chat c = chat.get();
        if (c == null) return;
        if (!accepted) {
            toast.accept("Esta red no acepta stickers");
            return;
        }
        svc.sendSticker(c.id(), item, takeReply.get()).whenComplete((v, err) -> Platform.runLater(() -> {
            if (err != null) {
                toast.accept("No se pudo enviar el sticker");
                return;
            }
            library.addRecent(item);
        }));
    }

    private static Label help(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("settings-help");
        return l;
    }
}

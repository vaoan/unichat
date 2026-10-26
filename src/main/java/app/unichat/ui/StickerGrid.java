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

    private final ChatService svc;
    private final MediaLibrary library;
    private final Supplier<Chat> chat;
    private final Consumer<String> toast;
    private final VBox content = new VBox(10);

    StickerGrid(ChatService svc, MediaLibrary library, Supplier<Chat> chat, Consumer<String> toast) {
        this.svc = svc;
        this.library = library;
        this.chat = chat;
        this.toast = toast;
        content.getStyleClass().add("sticker-grid");
        setContent(content);
        setFitToWidth(true);
        getStyleClass().add("side-panel-scroll");
    }

    /** Rebuilds the grid (opening the tab, after sending, after a favourite changes). */
    void refresh() {
        content.getChildren().clear();
        Chat c = chat.get();
        boolean accepted = c == null || c.features().accepts(RoomFeatures.STICKER);
        if (!accepted) content.getChildren().add(help("Esta red no acepta stickers."));
        section("Favoritos", library.favorites(MediaLibrary.Kind.STICKER), "Sticker favorito", accepted);
        section("Recientes", library.recents(MediaLibrary.Kind.STICKER), "Sticker reciente", accepted);
        if (library.favorites(MediaLibrary.Kind.STICKER).isEmpty() && library.recents(MediaLibrary.Kind.STICKER).isEmpty()) {
            content.getChildren().add(help("Aún no tienes stickers. Los que te manden aparecerán aquí, y puedes guardarlos con "
                    + "clic derecho → Agregar a favoritos."));
        }
    }

    private void section(String title, List<MediaLibrary.Item> items, String what, boolean accepted) {
        if (items.isEmpty()) return;
        Label t = new Label(title);
        t.getStyleClass().add("emoji-category");
        FlowPane grid = new FlowPane(6, 6);
        for (MediaLibrary.Item i : items) grid.getChildren().add(tile(i, what, accepted));
        content.getChildren().addAll(t, grid);
    }

    private Node tile(MediaLibrary.Item item, String what, boolean accepted) {
        ImageView view = new ImageView();
        view.setFitWidth(TILE);
        view.setFitHeight(TILE);
        view.setPreserveRatio(true);
        ThreadPane.loadMedia(svc, view, MediaItems.message(item), TILE * 2, TILE * 2);
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
        tile.setOnMouseClicked(e -> send.run());
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
        svc.sendSticker(c.id(), item).whenComplete((v, err) -> Platform.runLater(() -> {
            if (err != null) {
                toast.accept("No se pudo enviar el sticker");
                return;
            }
            library.addRecent(item);
            refresh();
        }));
    }

    private static Label help(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("settings-help");
        return l;
    }
}

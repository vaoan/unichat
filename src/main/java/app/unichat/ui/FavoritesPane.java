package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Favoritos tab of the side panel: every favourite emoji, sticker and GIF in one place. Clicking uses it (emoji into
 * the text, sticker/GIF to the open chat); right-click takes it out of the favourites.
 */
final class FavoritesPane extends ScrollPane {
    private final MediaLibrary library;
    private final StickerGrid stickers;
    private final GifGrid gifs;
    private final Consumer<String> insertEmoji;
    private final Consumer<String> toast;
    private final VBox content = new VBox(10);
    private final TileCache tiles = new TileCache();

    FavoritesPane(MediaLibrary library, StickerGrid stickers, GifGrid gifs, Consumer<String> insertEmoji, Consumer<String> toast) {
        this.library = library;
        this.stickers = stickers;
        this.gifs = gifs;
        this.insertEmoji = insertEmoji;
        this.toast = toast;
        StickerGrid.setUp(this, content, library, this::refresh);
    }

    void refresh() {
        tiles.next();
        content.getChildren().clear();
        section("Emojis", library.favorites(MediaLibrary.Kind.EMOJI), 2,
                i -> EmojiPicker.button(i.url(), insertEmoji, library, toast));
        section("Stickers", library.favorites(MediaLibrary.Kind.STICKER), 6, i -> stickers.tile(i, "Sticker favorito"));
        section("GIFs", library.favorites(MediaLibrary.Kind.GIF), 6, i -> gifs.libraryTile(i, "GIF favorito"));
        if (content.getChildren().isEmpty()) {
            Label l = new Label("Aquí tendrás juntos tus emojis, stickers y GIFs favoritos. Agrégalos con clic derecho → "
                    + "Agregar a favoritos, en un mensaje del chat o en las pestañas Emojis, Stickers y GIFs.");
            l.setWrapText(true);
            l.getStyleClass().add("settings-help");
            content.getChildren().add(l);
        }
    }

    private void section(String title, List<MediaLibrary.Item> items, double gap, Function<MediaLibrary.Item, Node> tile) {
        if (items.isEmpty()) return;
        Label t = new Label(title);
        t.getStyleClass().add("emoji-category");
        FlowPane grid = new FlowPane(gap, gap);
        for (MediaLibrary.Item i : items) grid.getChildren().add(tiles.get(i.kind().name() + stickers.accepts() + gifs.accepts() + i.url(), () -> tile.apply(i)));
        content.getChildren().addAll(t, grid);
    }
}

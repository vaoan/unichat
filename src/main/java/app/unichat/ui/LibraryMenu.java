package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.SeparatorMenuItem;

import java.util.function.Consumer;

/**
 * Right-click (or the menu key / Shift+F10) on an emoji, sticker or GIF of the side panel: use it, add it to or take
 * it out of the favourites, and drop it from the recents.
 */
final class LibraryMenu {
    private LibraryMenu() {}

    /** {@code use}: sends the sticker/GIF or inserts the emoji; null when it can't be used here. */
    static void attach(Node tile, MediaLibrary library, MediaLibrary.Item item, Runnable use, Consumer<String> toast) {
        tile.setOnContextMenuRequested(e -> {
            menu(library, item, use, toast).show(tile, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    static ContextMenu menu(MediaLibrary library, MediaLibrary.Item item, Runnable use, Consumer<String> toast) {
        ContextMenu menu = new ContextMenu();
        String what = switch (item.kind()) {
            case EMOJI -> "Emoji";
            case STICKER -> "Sticker";
            case GIF -> "GIF";
        };
        if (use != null) menu.getItems().add(ChatMenus.item(item.kind() == MediaLibrary.Kind.EMOJI ? "Insertar" : "Enviar", use));
        boolean fav = library.isFavorite(item.url());
        menu.getItems().add(ChatMenus.item(fav ? "Quitar de favoritos" : "Agregar a favoritos", () -> {
            boolean now = library.toggleFavorite(item);
            toast.accept(what + (now ? " agregado a favoritos" : " quitado de favoritos"));
        }));
        if (library.isRecent(item.url())) {
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(ChatMenus.item("Quitar de recientes", () -> library.removeRecent(item.url())));
        }
        return menu;
    }
}

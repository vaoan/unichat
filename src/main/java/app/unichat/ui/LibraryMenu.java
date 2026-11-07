package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import app.unichat.support.I18n;
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
            case EMOJI -> I18n.t("library.kind.emoji");
            case STICKER -> I18n.t("library.kind.sticker");
            case GIF -> I18n.t("library.kind.gif");
        };
        if (use != null) menu.getItems().add(ChatMenus.item(I18n.t(item.kind() == MediaLibrary.Kind.EMOJI ? "library.insert" : "library.send"), use));
        boolean fav = library.isFavorite(item);
        menu.getItems().add(ChatMenus.item(I18n.t(fav ? "message.removeFavorite" : "message.addFavorite"), () -> {
            boolean now = library.toggleFavorite(item);
            toast.accept(I18n.t(now ? "library.added" : "library.removed", what));
        }));
        if (library.isRecent(item)) {
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(ChatMenus.item(I18n.t("library.removeRecent"), () -> library.removeRecent(item)));
        }
        return menu;
    }
}

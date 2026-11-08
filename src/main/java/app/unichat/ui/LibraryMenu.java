package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import app.unichat.support.I18n;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.SeparatorMenuItem;

import java.util.function.Consumer;

/**
 * Right-click (or the menu key / Shift+F10) on an emoji, sticker or GIF of the side panel: use it, see it big, add it
 * to or take it out of the favourites, and drop it from the recents.
 */
final class LibraryMenu {
    private LibraryMenu() {}

    /** {@code use}: sends the sticker/GIF or inserts the emoji; null when it can't be used here. */
    /** {@code svc}: where the sticker or GIF comes from, for "see it big". */
    static void attach(Node tile, MediaLibrary library, MediaLibrary.Item item, Runnable use, Consumer<String> toast,
                       app.unichat.service.MediaSource svc) {
        tile.setOnContextMenuRequested(e -> {
            ContextMenu menu = menu(library, item, use, toast);
            if (item.url() != null) {
                menu.getItems().add(use == null ? 0 : 1, ChatMenus.item(I18n.t("library.enlarge"), () -> enlarge(tile, svc, item, toast)));
            }
            menu.show(tile, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /** In its own window, as big as it is: the picture viewer, or the video one for an mp4 GIF or a WebM sticker. */
    static void enlarge(Node from, app.unichat.service.MediaSource svc, MediaLibrary.Item item, Consumer<String> toast) {
        javafx.stage.Window owner = from.getScene().getWindow();
        app.unichat.model.Message m = MediaItems.message(item);
        String title = I18n.t(item.kind() == MediaLibrary.Kind.STICKER ? "library.kind.sticker" : "library.kind.gif");
        Runnable save = () -> MediaSaver.save(owner, svc, m, toast);
        var file = svc.media(item.url(), 0, 0);
        if (item.mime() != null && item.mime().startsWith("video/")) {
            VideoViewer.show(owner, file, title, save, p -> {
                try {
                    java.awt.Desktop.getDesktop().open(p.toFile());
                } catch (Exception ex) {
                    toast.accept(I18n.t("video.openFailed"));
                }
            });
        } else {
            MediaViewer.show(owner, file, title, save);
        }
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

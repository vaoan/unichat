package app.unichat.ui.emoji;

import app.unichat.ui.chats.ChatMenus;
import app.unichat.ui.media.MediaOpening;
import app.unichat.ui.media.MediaSaver;
import app.unichat.ui.media.MediaViewer;
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
public final class LibraryMenu {
    private LibraryMenu() {}

    /** {@code use}: sends the sticker/GIF or inserts the emoji; null when it can't be used here. */
    /** {@code svc}: where the sticker or GIF comes from, for "see it big". */
    static void attach(Node tile, MediaLibrary library, MediaLibrary.Item item, Runnable use, Consumer<String> toast,
                       app.unichat.service.MediaSource svc) {
        attach(tile, library, item, use, toast, svc, null);
    }

    /** {@code showSet}: opens a sticker's set (Telegram's), when it has one; null where there's nowhere to show it. */
    static void attach(Node tile, MediaLibrary library, MediaLibrary.Item item, Runnable use, Consumer<String> toast,
                       app.unichat.service.MediaSource svc, Consumer<String> showSet) {
        tile.setOnContextMenuRequested(e -> {
            ContextMenu menu = menu(library, item, use, toast);
            if (item.kind() != MediaLibrary.Kind.EMOJI && item.url() != null) {
                menu.getItems().add(use == null ? 0 : 1, ChatMenus.item(I18n.t("library.enlarge"), () -> enlarge(tile, svc, item, toast)));
            }
            String set = showSet == null ? null : app.unichat.model.StickerPack.urlOf(item.info());
            if (set != null) menu.getItems().add(use == null ? 1 : 2, ChatMenus.item(I18n.t("sticker.showSet"), () -> showSet.accept(set)));
            // Owned by the window, not the tile: a menu takes its owner's styles, and an emoji's (1.45 em, the emoji
            // font) made it huge, with the emoji font's wide spaces between words.
            menu.show(tile.getScene().getRoot(), e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /** In its own window, as big as it is: the picture viewer, or the video one for an mp4 GIF or a WebM sticker. */
    static void enlarge(Node from, app.unichat.service.MediaSource svc, MediaLibrary.Item item, Consumer<String> toast) {
        enlarge(from.getScene().getWindow(), svc, item, toast);
    }

    /** The same from a chat's message (a sticker's menu), over {@code owner}. */
    public static void enlarge(javafx.stage.Window owner, app.unichat.service.MediaSource svc, MediaLibrary.Item item, Consumer<String> toast) {
        app.unichat.model.Message m = MediaItems.message(item);
        String title = I18n.t(item.kind() == MediaLibrary.Kind.STICKER ? "library.kind.sticker" : "library.kind.gif");
        if (item.mime() != null && item.mime().startsWith("video/")) {
            MediaOpening.video(owner, svc, m, title, null, toast); // a sticker or GIF: silent, nothing to pause
        } else {
            MediaViewer.show(owner, svc.media(item.url(), 0, 0), title, () -> MediaSaver.save(owner, svc, m, toast));
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

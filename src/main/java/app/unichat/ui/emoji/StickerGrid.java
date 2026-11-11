package app.unichat.ui.emoji;

import app.unichat.ui.Labels;
import app.unichat.ui.media.MediaTile;
import app.unichat.ui.media.MediaLoading;
import app.unichat.ui.media.MessageMedia;
import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.RoomFeatures;
import app.unichat.service.ChatService;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
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
public final class StickerGrid extends ScrollPane {
    private static final int TILE = 76;

    private final Supplier<String> takeReply;
    private final ChatService svc;
    private final MediaLibrary library;
    private final Supplier<Chat> chat;
    private final Consumer<String> toast;
    private final VBox content = new VBox(10);
    private final TileCache tiles = new TileCache();
    private final StickerSetSection sets;

    public StickerGrid(ChatService svc, MediaLibrary library, Supplier<Chat> chat, Supplier<String> takeReply, Consumer<String> toast) {
        this.takeReply = takeReply;
        this.svc = svc;
        this.library = library;
        this.chat = chat;
        this.toast = toast;
        this.sets = new StickerSetSection(svc, this::refresh);
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
    public void refresh() {
        tiles.next();
        content.getChildren().clear();
        Chat c = chat.get();
        boolean accepted = c == null || c.features().accepts(RoomFeatures.STICKER);
        if (!accepted) content.getChildren().add(Labels.help(I18n.t("sticker.networkRefuses")));
        section(I18n.t("library.favorites"), library.favorites(MediaLibrary.Kind.STICKER), I18n.t("library.stickerFavorite"), accepted);
        section(I18n.t("library.recents"), library.recents(MediaLibrary.Kind.STICKER), I18n.t("library.stickerRecent"), accepted);
        List<Node> setNodes = sets.nodes((item, what) -> tiles.get(what + accepted + item.url(), () -> tile(item, what, accepted)),
                this::bringIntoView);
        if (library.favorites(MediaLibrary.Kind.STICKER).isEmpty() && library.recents(MediaLibrary.Kind.STICKER).isEmpty()
                && setNodes.isEmpty()) {
            content.getChildren().add(Labels.help(I18n.t("sticker.none")));
        }
        content.getChildren().addAll(setNodes);
    }

    /** Opens {@code url}'s sticker set in this tab (from one of its stickers' menu). */
    public void showPack(String url) {
        sets.show(url);
    }

    private void bringIntoView(Node n) {
        applyCss();
        layout();
        double max = content.getHeight() - getViewportBounds().getHeight();
        if (max > 0) setVvalue(Math.min(1, n.getBoundsInParent().getMinY() / max));
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
        Runnable send = () -> send(item, accepted);
        StackPane tile = MediaTile.of(view, TILE, TILE, "sticker-tile",
                I18n.t(accepted ? "library.tileSend" : "library.stickerTileRefused", what), accepted, send);
        MediaLoading.track(tile, progress -> MessageMedia.loadMedia(svc, view, MediaItems.message(item), TILE * 2, TILE * 2, progress));
        LibraryMenu.attach(tile, library, item, accepted ? send : null, toast, svc, this::showPack);
        return tile;
    }

    private void send(MediaLibrary.Item item, boolean accepted) {
        Chat c = chat.get();
        if (c == null) return;
        if (!accepted) {
            toast.accept(I18n.t("library.stickerNotAccepted"));
            return;
        }
        svc.sendSticker(c.id(), item, takeReply.get()).whenComplete((v, err) -> Platform.runLater(() -> {
            if (err != null) {
                toast.accept(I18n.t("sticker.sendFailed"));
                return;
            }
            library.addRecent(item);
        }));
    }
}

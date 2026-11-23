package app.unichat.ui.emoji;

import app.unichat.ui.Labels;
import app.unichat.ui.media.MediaTile;
import app.unichat.ui.media.MediaLoading;
import app.unichat.ui.media.MessageMedia;
import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.RoomFeatures;
import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.model.StickerPack;
import app.unichat.service.ChatService;
import app.unichat.service.FavoriteRefresh;
import app.unichat.settings.Settings;
import app.unichat.ui.Icons;
import app.unichat.ui.SearchFields;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.geometry.Pos;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Stickers tab of the side panel: your favourite and recent stickers (shared by every chat and network). A sticker
 * goes to the open chat; if its network doesn't take stickers the grid says so and nothing is sent.
 */
public final class StickerGrid extends ScrollPane {
    private static final int TILE = 76;
    /** The most a search shows (each tile loads its picture): a few screens; more words narrow it. */
    static final int MOST_FOUND = 60;

    private final Supplier<String> takeReply;
    private final ChatService svc;
    private final MediaLibrary library;
    private final Supplier<Chat> chat;
    private final Consumer<String> toast;
    private final VBox content = new VBox(10);
    private final TileCache tiles = new TileCache();
    final StickerSetSection sets;
    private boolean rebuilding;
    /** By itself, a rebuild some set failed in tries again only after this (each set is asked of Telegram sticker by sticker). */
    private static final long RETRY_MS = 10 * 60_000;
    private long notBefore;
    /** On top, kept across redraws (the focus stays in it while typing): a word or an emoji finds stickers. */
    private final TextField search = new TextField();
    private final Node searchBox;
    private EmojiSearch names;
    /** Runs at each redraw (a set arrived, a favourite changed): the suggestions follow. */
    private Runnable refreshed = () -> { };

    void onRefresh(Runnable r) {
        refreshed = r;
    }

    public StickerGrid(ChatService svc, MediaLibrary library, Supplier<Chat> chat, Supplier<String> takeReply, Consumer<String> toast) {
        this.takeReply = takeReply;
        this.svc = svc;
        this.library = library;
        this.chat = chat;
        this.toast = toast;
        this.sets = new StickerSetSection(svc, this::refresh);
        search.setPromptText(I18n.t("sticker.search"));
        search.setAccessibleText(I18n.t("sticker.search"));
        search.getStyleClass().add("emoji-search");
        search.textProperty().addListener(o -> refresh());
        searchBox = SearchFields.clearable(search);
        content.getChildren().add(searchBox);
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
        refreshed.run();
        tiles.next();
        content.getChildren().remove(1, content.getChildren().size()); // all but the search, which keeps the focus
        Chat c = chat.get();
        boolean accepted = c == null || c.features().accepts(RoomFeatures.STICKER);
        if (!accepted) content.getChildren().add(Labels.help(I18n.t("sticker.networkRefuses")));
        if (!search.getText().isBlank()) {
            found(search.getText(), accepted);
            return;
        }
        String favorite = I18n.t("library.stickerFavorite"), recent = I18n.t("library.stickerRecent");
        section(I18n.t("library.favorites"), library.favorites(MediaLibrary.Kind.STICKER), i -> favorite, accepted,
                Icons.iconButton(Icons.REPLAY, I18n.t("library.rebuildFavorites"), () -> rebuild(false)));
        section(I18n.t("library.recents"), library.recents(MediaLibrary.Kind.STICKER), i -> recent, accepted, null);
        List<Node> setNodes = sets.nodes((item, what) -> tiles.get(what + accepted + item.url(), () -> tile(item, what, accepted)),
                this::bringIntoView);
        if (library.favorites(MediaLibrary.Kind.STICKER).isEmpty() && library.recents(MediaLibrary.Kind.STICKER).isEmpty()
                && setNodes.isEmpty()) {
            content.getChildren().add(Labels.help(I18n.t("sticker.none")));
        }
        content.getChildren().addAll(setNodes);
    }

    /**
     * What {@code query} finds: the stickers of the emoji it names or has (favourites, recents, then the sets, each
     * once), and the sets whose name it has. Every set of yours is brought for it, each showing as it comes.
     */
    private void found(String query, boolean accepted) {
        List<MediaLibrary.Item> hits = StickerFinder.find(StickerFinder.wanted(query, names(), allEmoji()), sources());
        List<StickerPack> named = StickerFinder.setsNamed(query, sets.loaded());
        if (hits.isEmpty() && named.isEmpty()) {
            content.getChildren().add(Labels.help(I18n.t("sticker.searchNone", query.strip())));
            return;
        }
        if (hits.size() > MOST_FOUND) content.getChildren().add(Labels.help(I18n.t("sticker.searchMore", MOST_FOUND, hits.size())));
        section(I18n.t("sticker.searchFound"), hits.subList(0, Math.min(MOST_FOUND, hits.size())),
                i -> StickerFinder.says("library.stickerFound", i), accepted, null);
        for (StickerPack p : named) {
            section(p.title(), p.stickers().subList(0, Math.min(MOST_FOUND, p.stickers().size())),
                    i -> StickerSetSection.inSet(i, p.title()), accepted, null);
        }
    }

    /** The stickers whose emoji is {@code emoji}, from favourites, recents and the sets brought: suggestions for it. */
    List<MediaLibrary.Item> withEmoji(String emoji) {
        return StickerFinder.find(Set.of(StickerFinder.plain(emoji)), sources());
    }

    /** Where stickers are looked for, in order: favourites, recents, then every set (those not here yet are brought). */
    private List<List<MediaLibrary.Item>> sources() {
        sets.loadAll();
        List<MediaLibrary.Item> fromSets = new ArrayList<>();
        for (StickerPack p : sets.loaded()) fromSets.addAll(p.stickers());
        return List.of(library.favorites(MediaLibrary.Kind.STICKER), library.recents(MediaLibrary.Kind.STICKER), fromSets);
    }

    private EmojiSearch names() {
        if (names == null) names = EmojiSearch.forLanguage(I18n.locale().getLanguage());
        return names;
    }

    /** Every emoji, in the picker's order. */
    static List<String> allEmoji() {
        return EmojiPicker.CATEGORIES.values().stream().flatMap(List::stream).toList();
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

    private void section(String title, List<MediaLibrary.Item> items, Function<MediaLibrary.Item, String> what, boolean accepted, Node action) {
        if (items.isEmpty()) return;
        Label t = new Label(title);
        t.getStyleClass().add("emoji-category");
        Node header = t;
        if (action != null) {
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox row = new HBox(4, t, gap, action);
            row.setAlignment(Pos.CENTER_LEFT);
            header = row;
        }
        FlowPane grid = new FlowPane(6, 6);
        for (MediaLibrary.Item i : items) {
            String w = what.apply(i);
            grid.getChildren().add(tiles.get(w + accepted + i.url(), () -> tile(i, w, accepted)));
        }
        content.getChildren().addAll(header, grid);
    }

    /**
     * Once on this computer, when a Telegram account is connected: the favourites saved before Unichat got Telegram's
     * stickers as they are (video stickers as GIFs, no size) are rebuilt from their sets. Done for good only when every
     * set could be brought.
     */
    public void rebuildOnce(List<Account> accounts) {
        if (Settings.get().favoritesRebuilt() || System.currentTimeMillis() < notBefore) return;
        if (accounts.stream().noneMatch(a -> a.network() == Network.TELEGRAM && a.status() == AccountStatus.CONNECTED)) return;
        rebuild(true);
    }

    /** Rebuilds the favourite stickers from their sets ({@link FavoriteRefresh}); by itself ({@code once}) says only what changed. */
    private void rebuild(boolean once) {
        if (rebuilding) return;
        rebuilding = true;
        if (!once) toast.accept(I18n.t("library.rebuilding"));
        var _ = FavoriteRefresh.run(svc, library).whenComplete((r, err) -> Platform.runLater(() -> {
            rebuilding = false;
            if (r == null) {
                notBefore = System.currentTimeMillis() + RETRY_MS;
                if (!once) toast.accept(I18n.t("library.rebuildFailed"));
                return;
            }
            if (r.failedSets() == 0) Settings.get().setFavoritesRebuilt(true);
            else notBefore = System.currentTimeMillis() + RETRY_MS;
            if (!once || r.rebuilt() > 0) toast.accept(I18n.n("library.rebuilt", r.rebuilt()));
        }));
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

    /**
     * A tile for {@code accepted} (whether the open chat takes stickers), that asks {@code after} when clicked what to
     * run once its sticker went (a suggestion empties the message box, if it still holds what the sticker replaced).
     */
    Node tile(MediaLibrary.Item item, String what, boolean accepted, Supplier<Runnable> after) {
        ImageView view = new ImageView();
        Runnable send = () -> send(item, accepted, after);
        StackPane tile = MediaTile.of(view, TILE, TILE, "sticker-tile",
                I18n.t(accepted ? "library.tileSend" : "library.stickerTileRefused", what), accepted, send);
        MediaLoading.track(tile, progress -> MessageMedia.loadMedia(svc, view, MediaItems.message(item), TILE * 2, TILE * 2, progress));
        LibraryMenu.attach(tile, library, item, accepted ? send : null, toast, svc, this::showPack);
        return tile;
    }

    private Node tile(MediaLibrary.Item item, String what, boolean accepted) {
        return tile(item, what, accepted, () -> () -> { });
    }

    private void send(MediaLibrary.Item item, boolean accepted, Supplier<Runnable> afterSent) {
        Chat c = chat.get();
        if (c == null) return;
        if (!accepted) {
            toast.accept(I18n.t("library.stickerNotAccepted"));
            return;
        }
        Runnable after = afterSent.get(); // asked now: what the box holds at the click
        var _ = svc.sendSticker(c.id(), item, takeReply.get()).whenComplete((v, err) -> Platform.runLater(() -> {
            if (err != null) {
                toast.accept(I18n.t("sticker.sendFailed"));
                return;
            }
            library.addRecent(item);
            after.run();
        }));
    }
}

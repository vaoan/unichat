package app.unichat.ui;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

/** Left column: title, search, filter chips, favorites grid and the chat list. */
public final class ChatListPane extends VBox {
    private final ChatService svc;
    private final ObjectProperty<ChatFilter> filter = new SimpleObjectProperty<>(ChatFilter.DEFAULT);
    private final ObservableList<Chat> items = FXCollections.observableArrayList();
    private final ListView<Chat> list = new ListView<>(items);
    private final FlowPane favorites = new FlowPane();
    private final FlowPane accountRow = new FlowPane(6, 6);
    private final Label title = new Label();
    private final Label empty = new Label();
    private final TextField search = new TextField();
    private final ToggleGroup chips = new ToggleGroup();
    private final Spaces spaces;
    private FlowPane chipRow;
    private Runnable onNewChat;
    private final Consumer<Chat> onOpen;
    private String selectedId;
    /** What the favorites row was built from ({@link #showFavorites}). */
    private String favoritesShown;
    private boolean updating;
    /** The server row the keyboard is on, if any (it opens on Enter, not on arrowing). */
    private String arrowedRow;
    private InboxRows.Rows rows = InboxRows.Rows.EMPTY;
    /** The accounts as of the last refresh: list cells need them, and computing them walks every room. */
    private List<Account> accounts = List.of();
    /** A server's channels, covering the list while open. */
    private final SpaceChannelsView channels;
    private javafx.scene.control.Button newChat;
    private final FolderDialogs folderDialogs;
    private final FolderView folderView;
    /** In folders (the names, one folder, or a server opened from one). */
    private final BooleanProperty inFolders = new SimpleBooleanProperty();

    public ChatListPane(ChatService svc, Consumer<Chat> onOpen, FolderDialogs folderDialogs) {
        this.svc = svc;
        this.onOpen = onOpen;
        this.folderDialogs = folderDialogs;
        this.spaces = new Spaces(svc, c -> open(c));
        spaces.setOnChanged(this::refresh);
        getStyleClass().add("chat-list-pane");

        Node header = buildHeader();
        Node searchBox = buildSearchBox();

        chipRow = new FlowPane(6, 6,
                chip(I18n.t("chatList.chip.all"), f -> f.withUnreadOnly(false).withView(ChatFilter.View.INBOX)),
                chip(I18n.t("chatList.chip.unread"), f -> f.withUnreadOnly(true).withView(ChatFilter.View.INBOX)),
                chip(I18n.t("chatList.chip.favorites"), f -> f.withUnreadOnly(false).withView(ChatFilter.View.FAVORITES)),
                chip(I18n.t("chatList.chip.lowPriority"), f -> f.withUnreadOnly(false).withView(ChatFilter.View.LOW_PRIORITY)));
        chipRow.getStyleClass().add("chip-row");
        chips.getToggles().get(0).setSelected(true);
        chips.selectedToggleProperty().addListener((o, a, b) -> { if (b == null) a.setSelected(true); });

        favorites.getStyleClass().add("favorites");
        favorites.setHgap(4);
        favorites.setVgap(4);

        configureList();

        accountRow.getStyleClass().addAll("chip-row", "account-row");
        accountRow.setAccessibleText(I18n.t("chatList.accounts"));
        channels = new SpaceChannelsView(svc, spaces, this::newCell, this::open, this::rowMenu, this::closeSpace);
        folderView = new FolderView(svc, folderDialogs, this::newCell, this::open);
        folderView.setOnLeave(() -> {
            goTo(filter.get().switchTo(null, null));
            list.requestFocus();
        });
        ChatMenus.onMenuKey(folderView.chatList(), this::rowMenu);
        VBox.setVgrow(folderView, Priority.ALWAYS);
        getChildren().addAll(header, searchBox, chipRow, accountRow, favorites, list, channels, folderView);
        filter.addListener((o, a, b) -> {
            syncChips(b);
            refresh();
        });
        refresh();
    }

    /** Goes under the list, whether the inbox or a server's channels are showing. */
    void setFooter(Node footer) {
        getChildren().add(footer);
    }

    private Node buildHeader() {
        title.getStyleClass().add("pane-title");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        newChat = Icons.iconButton(Icons.PLUS, I18n.t("chatList.newChat"), () -> { if (onNewChat != null) onNewChat.run(); });
        newChat.setTooltip(new javafx.scene.control.Tooltip(I18n.t("chatList.newChatTip", Shortcuts.key("N"))));
        HBox header = new HBox(6, title, headerSpacer, newChat);
        header.getStyleClass().add("chat-list-header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Node buildSearchBox() {
        search.setPromptText(I18n.t("chatList.searchPrompt", Shortcuts.key("K")));
        search.getStyleClass().add("search-field");
        search.setAccessibleText(I18n.t("chatList.searchLabel"));
        search.setAccessibleHelp(I18n.t("chatList.searchHelp"));
        search.textProperty().addListener((o, a, q) -> filter.set(filter.get().withQuery(q)));
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) search.clear();
            if (e.getCode() == KeyCode.DOWN) { list.requestFocus(); list.getSelectionModel().selectFirst(); }
            if (e.getCode() == KeyCode.ENTER && !items.isEmpty()) open(items.get(0));
        });
        Node searchIcon = Icons.icon(Icons.SEARCH, 14);
        StackPane searchBox = new StackPane(search, searchIcon);
        StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
        StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 10));
        searchIcon.setMouseTransparent(true);
        searchBox.getStyleClass().add("search-box");
        SearchFields.addClear(searchBox, search);
        return searchBox;
    }

    private ListCell<Chat> newCell() {
        return new ChatRowCell(this, svc, spaces);
    }

    private void configureList() {
        list.getStyleClass().add("chat-list");
        list.setAccessibleText(I18n.t("chatList.chats"));
        list.setCellFactory(v -> newCell());
        list.setOnKeyPressed(e -> {
            Chat sel = list.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER || (e.getCode() == KeyCode.RIGHT && sel != null && sel.space())) {
                open(sel);
                e.consume();
            }
        });
        // Arrowing onto a chat opens it; a server or a channel to join waits for Enter or a click.
        list.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> {
            if (updating) return;
            arrowedRow = c != null && !channels.actsLikeChat(c) ? c.id() : null;
            // After the list has finished selecting: opening redraws and reselects it, and doing that while the
            // list is still telling its own listeners about the click made JavaFX fail (IndexOutOfBoundsException).
            if (c != null && list.isFocused() && channels.actsLikeChat(c)) Platform.runLater(() -> {
                if (list.getSelectionModel().getSelectedItem() == c) open(c);
            });
        });
        empty.getStyleClass().add("empty-label");
        empty.setWrapText(true);
        list.setPlaceholder(empty);
        ChatMenus.onMenuKey(list, this::rowMenu);
        VBox.setVgrow(list, Priority.ALWAYS);
    }

    private ToggleButton chip(String text, java.util.function.UnaryOperator<ChatFilter> change) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("chip");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setToggleGroup(chips);
        b.setOnAction(e -> filter.set(change.apply(filter.get())));
        return b;
    }

    /** The chips follow the filter however it changed (rail, shortcuts, leaving the archive). */
    private void syncChips(ChatFilter f) {
        int i = switch (f.view()) {
            case INBOX -> f.unreadOnly() ? 1 : 0;
            case FAVORITES -> 2;
            case LOW_PRIORITY -> 3;
            case ARCHIVE -> -1;
        };
        if (i >= 0 && chips.getToggles().get(i) != chips.getSelectedToggle()) chips.getToggles().get(i).setSelected(true);
    }

    public ObjectProperty<ChatFilter> filterProperty() { return filter; }

    /**
     * Navigation (rail, shortcuts, menus): leaves whatever server is open and shows {@code f}, even when it's the
     * filter already in place (clicking Bandeja from inside a server must take you to the inbox).
     */
    public void goTo(ChatFilter f) {
        channels.close();
        leaveFolders();
        if (f.equals(filter.get())) refresh();
        else filter.set(f);
    }

    public void setOnNewChat(Runnable r) { this.onNewChat = r; }

    public void setToast(Consumer<String> toast) { spaces.setToast(toast); }

    public void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    /** Focuses the chat list (F6 cycle), keeping the open chat selected. */
    public void focusList() {
        if (!channels.isOpen() && foldersOpen()) {
            folderView.focus();
            return;
        }
        if (channels.isOpen()) {
            channels.focusList();
            return;
        }
        list.requestFocus();
        if (list.getSelectionModel().isEmpty() && !items.isEmpty()) list.getSelectionModel().select(0);
    }

    public void setSelected(String chatId) {
        selectedId = chatId;
        selectInList();
        folderView.select(chatId);
    }

    /** Rebuilds the visible list from the service; call on the FX thread after chatsChanged. */
    public void refresh() {
        ChatFilter f = filter.get();
        List<Chat> all = svc.chats();
        accounts = svc.accounts();
        rows = InboxRows.build(all, f, spaces.names());
        List<Chat> visible = new java.util.ArrayList<>(rows.rows());
        spaces.checkPending();
        visible.addAll(offerRows(f));
        if (channels.isOpen() && (f.searching() || svc.chat(channels.openId()) == null)) channels.close();
        if (f.searching()) leaveFolders();
        if (foldersOpen() && !channels.isOpen()) folderView.refresh(all); // closeSpace refreshes it
        channels.refresh(all, f);
        updating = true;
        try {
            ListRows.show(items, visible, Chat::id);
            channels.showRows(this::selectInList);
        } finally {
            updating = false;
        }
        ListRows.redraw(list);
        channels.redraw();

        String base = switch (f.view()) {
            case INBOX -> f.unreadOnly() ? I18n.t("chatList.title.unread") : I18n.t("chatList.title.inbox");
            case FAVORITES -> I18n.t("chatList.title.favorites");
            case LOW_PRIORITY -> I18n.t("chatList.title.lowPriority");
            case ARCHIVE -> I18n.t("chatList.title.archived");
        };
        newChat.setVisible(!foldersOpen()); // beside the title Carpetas, + would read as a new folder
        title.setText(foldersOpen() ? I18n.t("chatList.title.folders") : f.network() == null ? base : f.network().displayName());
        refreshAccountRow(f);
        refreshView(f);
        empty.setText(f.searching() ? I18n.t("chatList.empty.noResults", f.query())
                : f.view() == ChatFilter.View.ARCHIVE ? I18n.t("chatList.empty.noArchived")
                : f.unreadOnly() ? I18n.t("chatList.empty.allRead")
                : svc.accounts().stream().noneMatch(a -> a.status() == AccountStatus.CONNECTED)
                    ? I18n.t("chatList.empty.noNetwork")
                : I18n.t("chatList.empty.none"));

        boolean showFavs = f.view() == ChatFilter.View.INBOX && !f.searching() && !f.unreadOnly() && !channels.isOpen() && !foldersOpen();
        List<Chat> favs = showFavs ? all.stream().filter(c -> c.favorite() && !c.personalSpace() && !c.archived() && !c.bridgeBot()
                && (f.network() == null || c.network() == f.network())).sorted(ChatFilter.ORDER).limit(8).toList() : List.of();
        List<app.unichat.service.Folder> favFolders = showFavs && f.network() == null
                ? svc.folders().stream().filter(app.unichat.service.Folder::favorite).toList() : List.of();
        showFavorites(favs, favFolders, all);
    }

    /**
     * The favorites row, built again only when what it shows changed: it's refreshed on every change to the list (opening
     * a chat marks it read), and new tiles repaint every avatar. The selection and the unread dot change on the tiles there.
     */
    private void showFavorites(List<Chat> favs, List<app.unichat.service.Folder> folders, List<Chat> all) {
        StringBuilder shown = new StringBuilder(Settings.get().showNetworkBadges() ? "b" : "");
        for (Chat c : favs) {
            shown.append('|').append(c.id()).append(c.title()).append(c.avatarUrl()).append(c.network());
        }
        for (app.unichat.service.Folder fo : folders) shown.append("|folder ").append(fo.id()).append(fo.name());
        if (!shown.toString().equals(favoritesShown)) {
            favoritesShown = shown.toString();
            List<Node> tiles = new java.util.ArrayList<>(favs.stream().map(this::favoriteTile).toList());
            folders.forEach(fo -> tiles.add(favoriteFolderTile(fo)));
            favorites.getChildren().setAll(tiles);
        }
        for (int i = 0; i < favs.size(); i++) {
            javafx.scene.Node tile = favorites.getChildren().get(i);
            Chat c = favs.get(i);
            Nodes.style(tile, "selected", c.id().equals(selectedId));
            tile.lookup(".unread-dot").setVisible(c.space() ? rows.unread().getOrDefault(c.id(), 0) > 0 : c.isUnread());
            tile.setAccessibleText(I18n.t("chatList.favoriteA11y", A11yText.chat(c, Accounts.forChat(accounts, c), Instant.now(), ZoneId.systemDefault())));
        }
        for (int i = 0; i < folders.size(); i++) {
            javafx.scene.Node tile = favorites.getChildren().get(favs.size() + i);
            long unread = FolderRows.unread(folders.get(i), all);
            tile.lookup(".unread-dot").setVisible(unread > 0);
            tile.setAccessibleText(I18n.t("chatList.favoriteFolderA11y", folders.get(i).name(), unread > 0 ? ", " + I18n.n("rail.unread", (int) unread) : ""));
        }
        Nodes.show(favorites, !favs.isEmpty() || !folders.isEmpty());
    }

    /** A favorite folder: its folder icon where a chat has its picture; opens the folder. */
    private Node favoriteFolderTile(app.unichat.service.Folder fo) {
        StackPane icon = new StackPane(Icons.icon(Icons.FOLDER, 22));
        icon.getStyleClass().add("favorite-folder-icon");
        icon.setMinSize(44, 44);
        icon.setMaxSize(44, 44);
        StackPane dot = new StackPane();
        dot.getStyleClass().add("unread-dot");
        StackPane.setAlignment(dot, Pos.TOP_RIGHT);
        StackPane av = new StackPane(icon, dot);
        av.setMaxSize(44, 44);
        VBox tile = new VBox(4, av, favoriteName(fo.name(), fo.name()));
        tile.setAlignment(Pos.TOP_CENTER);
        tile.getStyleClass().addAll("favorite-tile", "favorite-folder");
        tile.setUserData("folder:" + fo.id());
        Runnable open = () -> showFolder(fo.name());
        tile.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) open.run();
        });
        tile.setFocusTraversable(true);
        tile.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        tile.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) open.run();
        });
        return tile;
    }

    /** The name under a favorite tile: centered and cut with "…" in the tile's width (em), the whole one on hover. */
    private static Label favoriteName(String shown, String whole) {
        Label name = new Label(shown);
        name.getStyleClass().add("favorite-name");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setAlignment(Pos.CENTER);
        name.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        name.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        name.setEllipsisString("…");
        name.setTooltip(new javafx.scene.control.Tooltip(whole));
        return name;
    }

    /**
     * Servers you're in that aren't in Unichat yet (Discord), one row each among the chats. They have no messages, so
     * they come last; a search finds them by name.
     */
    private List<Chat> offerRows(ChatFilter f) {
        if (f.view() != ChatFilter.View.INBOX || f.unreadOnly()) return List.of();
        List<Chat> out = new java.util.ArrayList<>();
        List<Network> nets = f.network() != null ? List.of(f.network())
                : java.util.Arrays.stream(Network.values()).filter(Network::isBridged).toList();
        for (Network n : nets) {
            for (ChatService.GroupOffer o : spaces.offers(n)) {
                if (f.searching() && !ChatFilter.normalize(o.name()).contains(ChatFilter.normalize(f.query()))) continue;
                Chat p = new Chat(Spaces.OFFER_PREFIX + o.id());
                p.setTitle(o.name());
                p.setNetwork(n);
                out.add(p);
            }
        }
        return out;
    }

    /** A server's channels cover the list while open. */
    private void refreshView(ChatFilter f) {
        boolean space = channels.isOpen();
        boolean folders = !space && foldersOpen();
        for (Node x : List.of(chipRow, list)) {
            Nodes.show(x, !space && !folders);
        }
        Nodes.show(folderView, folders);
        channels.setBackText(foldersOpen() ? I18n.t("chatList.backToFolder") : I18n.t("chatList.backToInbox"));
        if (space) {
            Nodes.show(accountRow, false);
        }
        Nodes.show(channels, space);
    }

    /** "Todas · Personal · Trabajo" chips, shown only for a network with several accounts. */
    private void refreshAccountRow(ChatFilter f) {
        List<Account> accounts = f.network() == null ? List.of() : Accounts.of(this.accounts, f.network());
        // Only offer per-account filters when every chat of the network knows its account; otherwise a filter
        // would silently hide chats (Matrix bridges don't always say which login a portal belongs to).
        boolean attributed = f.network() != null && svc.chats().stream()
                .filter(c -> c.network() == f.network() && !c.bridgeBot()).allMatch(c -> c.accountId() != null);
        boolean show = accounts.size() > 1 && !f.searching() && attributed;
        Nodes.show(accountRow, show);
        accountRow.getChildren().clear();
        if (!show) return;
        ToggleGroup group = new ToggleGroup();
        accountRow.getChildren().add(accountChip(I18n.t("chatList.allAccounts"), f.accountId() == null, group,
                () -> filter.set(filter.get().withAccount(f.network(), null))));
        for (Account a : accounts) {
            accountRow.getChildren().add(accountChip(Accounts.name(a), a.id().equals(f.accountId()), group,
                    () -> filter.set(filter.get().withAccount(f.network(), a.id()))));
        }
    }

    private ToggleButton accountChip(String text, boolean selected, ToggleGroup group, Runnable action) {
        ToggleButton b = Chips.sticky(text, group, selected, action);
        b.getStyleClass().add("account-chip");
        return b;
    }

    private void selectInList() {
        // A server or a channel to join being arrowed through stays selected across redraws (it isn't "open").
        String keep = arrowedRow;
        if (keep != null && list.isFocused()) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id().equals(keep)) {
                    list.getSelectionModel().select(i);
                    return;
                }
            }
        }
        channels.select(selectedId);
        if (selectedId == null) {
            list.getSelectionModel().clearSelection();
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(selectedId)) {
                list.getSelectionModel().select(i);
                return;
            }
        }
        list.getSelectionModel().clearSelection();
    }

    private Node favoriteTile(Chat c) {
        Avatar a = new Avatar(44);
        a.set(c.title(), c.id(), c.avatarUrl(), svc);
        a.setNetwork(c.network(), Settings.get().showNetworkBadges());
        Label name = favoriteName(Formats.favoriteName(c.title()), c.title());
        StackPane dot = new StackPane();
        dot.getStyleClass().add("unread-dot");
        dot.setVisible(c.space() ? rows.unread().getOrDefault(c.id(), 0) > 0 : c.isUnread());
        StackPane.setAlignment(dot, Pos.TOP_RIGHT);
        StackPane av = new StackPane(a, dot);
        av.setMaxSize(44, 44);
        VBox tile = new VBox(4, av, name);
        tile.setAlignment(Pos.TOP_CENTER);
        tile.getStyleClass().add("favorite-tile");
        tile.setUserData(c.id());
        tile.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) open(c);
        });
        tile.setFocusTraversable(true);
        tile.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        tile.setAccessibleText(I18n.t("chatList.favoriteA11y", A11yText.chat(c, Accounts.forChat(accounts, c), Instant.now(), ZoneId.systemDefault())));
        tile.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) open(c);
        });
        tile.setOnContextMenuRequested(e -> menuFor(c).show(this, e.getScreenX(), e.getScreenY()));
        return tile;
    }

    /** Shows a server's channels over the list. */
    private void openSpace(String id) {
        arrowedRow = id;
        channels.open(id);
        refresh();
        channels.focusOnOpening();
    }

    /** Back to the list, on the server you came from. */
    private void closeSpace() {
        String was = channels.openId();
        channels.close();
        refresh();
        if (foldersOpen()) {
            folderView.focusOn(was);
            return;
        }
        list.requestFocus();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(was)) {
                arrowedRow = was;
                updating = true;
                list.getSelectionModel().select(i);
                list.scrollTo(i);
                updating = false;
                break;
            }
        }
    }

    public void showSpace(String id) {
        if (svc.chat(id) != null) openSpace(id);
    }

    /** Whether a server's channels are covering the list (Esc goes back). */
    public boolean spaceOpen() {
        return channels.isOpen();
    }

    /** Shows the list of folders. A network filter would leave out part of what a folder's servers count, so it goes. */
    public void showFolders() {
        channels.close();
        search.clear();
        folderView.reset();
        inFolders.set(true);
        ChatFilter all = filter.get().switchTo(null, null);
        if (all.equals(filter.get())) refresh();
        else filter.set(all);
        folderView.focus();
    }

    /** Goes into the folder called {@code name} (snapshot diagnostics). */
    public void showFolder(String name) {
        showFolders();
        folderView.openNamed(name);
    }

    public boolean foldersOpen() { return inFolders.get(); }

    BooleanProperty foldersOpenProperty() { return inFolders; }

    private void leaveFolders() {
        folderView.reset();
        inFolders.set(false);
    }

    ContextMenu menuFor(Chat c) {
        ContextMenu menu = c.space() ? ChatMenus.forSpace(c, svc, folderDialogs) : ChatMenus.forChat(c, svc, folderDialogs, null);
        if (!channels.isOpen() && foldersOpen()) folderView.offerRemoval(menu, c);
        return menu;
    }

    /** The menu of a row, if it has one: a channel to join or a server to add aren't chats yet. */
    ContextMenu rowMenu(Chat c) {
        return c.space() || channels.actsLikeChat(c) ? menuFor(c) : null;
    }

    void open(Chat c) {
        if (c == null) return;
        if (c.space()) {
            openSpace(c.id());
            return;
        }
        if (Spaces.isOffer(c)) {
            spaces.add(c.network(), Spaces.offerId(c), c.title());
            return;
        }
        if (channels.isUnjoined(c)) {
            spaces.join(c.id(), c.title());
            return;
        }
        selectedId = c.id();
        onOpen.accept(c);
    }

    /** Opens the next/previous visible chat (Alt+↓ / Alt+↑). */
    public void move(int delta, boolean unreadOnly) {
        boolean folder = !channels.isOpen() && foldersOpen();
        List<Chat> items = folder ? folderView.rows() : this.items;
        if (items.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(selectedId)) idx = i;
        for (int step = 1; step <= items.size(); step++) {
            int i = Math.floorMod(idx + delta * step, items.size());
            if (idx < 0) i = delta > 0 ? step - 1 : items.size() - step;
            Chat c = items.get(i);
            if (channels.actsLikeChat(c) && (!unreadOnly || c.isUnread())) {
                open(c);
                if (folder) folderView.reveal(i);
                else list.scrollTo(i);
                return;
            }
        }
    }

    // What its rows draw from.

    SpaceChannelsView channels() { return channels; }

    String selectedId() { return selectedId; }

    List<Account> accounts() { return accounts; }

    InboxRows.Rows rows() { return rows; }
}

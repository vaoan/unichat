package app.unichat.ui;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
import java.util.Objects;
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
    private boolean updating;
    /** The server row the keyboard is on, if any (it opens on Enter, not on arrowing). */
    private String arrowedRow;
    private InboxRows.Rows rows = InboxRows.Rows.EMPTY;
    /** The server whose channels cover the list (Telegram-forum style), or null. */
    private String openSpace;
    /** The accounts as of the last refresh: list cells need them, and computing them walks every room. */
    private List<Account> accounts = List.of();
    private InboxRows.Rows spaceRows = InboxRows.Rows.EMPTY;
    private final ObservableList<Chat> spaceItems = FXCollections.observableArrayList();
    private final ListView<Chat> spaceList = new ListView<>(spaceItems);
    private final Label spaceTitle = new Label();
    private final javafx.scene.control.Button markSpaceRead =
            Icons.iconButton(Icons.CHECK_DOUBLE, "Marcar todo como leído", this::markSpaceRead);
    private final java.util.Set<String> channelsAsked = new java.util.HashSet<>();
    private final VBox spaceView;
    private javafx.scene.control.Button spaceBack;
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
                chip("Todos", f -> f.withUnreadOnly(false).withView(ChatFilter.View.INBOX)),
                chip("No leídos", f -> f.withUnreadOnly(true).withView(ChatFilter.View.INBOX)),
                chip("Favoritos", f -> f.withUnreadOnly(false).withView(ChatFilter.View.FAVORITES)),
                chip("Prioridad baja", f -> f.withUnreadOnly(false).withView(ChatFilter.View.LOW_PRIORITY)));
        chipRow.getStyleClass().add("chip-row");
        chips.getToggles().get(0).setSelected(true);
        chips.selectedToggleProperty().addListener((o, a, b) -> { if (b == null) a.setSelected(true); });

        favorites.getStyleClass().add("favorites");
        favorites.setHgap(4);
        favorites.setVgap(4);

        configureList();

        accountRow.getStyleClass().addAll("chip-row", "account-row");
        accountRow.setAccessibleText("Cuentas");
        HBox spaceHeader = buildSpaceHeader();
        configureSpaceList();
        spaceView = new VBox(6, spaceHeader, spaceList);
        spaceView.getStyleClass().add("space-view");
        VBox.setVgrow(spaceView, Priority.ALWAYS);
        Nodes.show(spaceView, false);
        folderView = new FolderView(svc, folderDialogs, ChatCell::new, this::open);
        folderView.setOnLeave(() -> {
            goTo(filter.get().switchTo(null, null));
            list.requestFocus();
        });
        ChatMenus.onMenuKey(folderView.chatList(), this::rowMenu);
        VBox.setVgrow(folderView, Priority.ALWAYS);
        getChildren().addAll(header, searchBox, chipRow, accountRow, favorites, list, spaceView, folderView);
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
        newChat = Icons.iconButton(Icons.PLUS, "Nuevo chat", () -> { if (onNewChat != null) onNewChat.run(); });
        newChat.setTooltip(new javafx.scene.control.Tooltip("Nuevo chat (" + Shortcuts.key("N") + ")"));
        HBox header = new HBox(6, title, headerSpacer, newChat);
        header.getStyleClass().add("chat-list-header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Node buildSearchBox() {
        search.setPromptText("Buscar   " + Shortcuts.key("K"));
        search.getStyleClass().add("search-field");
        search.setAccessibleText("Buscar chats");
        search.setAccessibleHelp("Flecha abajo para ir a los resultados, Enter abre el primero");
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
        return searchBox;
    }

    private void configureList() {
        list.getStyleClass().add("chat-list");
        list.setAccessibleText("Chats");
        list.setCellFactory(v -> new ChatCell());
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
            arrowedRow = c != null && !actsLikeChat(c) ? c.id() : null;
            if (c != null && list.isFocused() && actsLikeChat(c)) open(c);
        });
        empty.getStyleClass().add("empty-label");
        empty.setWrapText(true);
        list.setPlaceholder(empty);
        ChatMenus.onMenuKey(list, this::rowMenu);
        VBox.setVgrow(list, Priority.ALWAYS);
    }

    private HBox buildSpaceHeader() {
        // A server's channels slide over the list, like a Telegram forum's topics.
        spaceBack = Icons.iconButton(Icons.CHEVRON_LEFT, "Volver a la bandeja", this::closeSpace);
        spaceTitle.getStyleClass().add("space-title");
        spaceTitle.setMinWidth(0);
        spaceTitle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(spaceTitle, Priority.ALWAYS);
        HBox spaceHeader = new HBox(8, spaceBack, spaceTitle, markSpaceRead);
        spaceHeader.setAlignment(Pos.CENTER_LEFT);
        spaceHeader.getStyleClass().add("space-header");
        return spaceHeader;
    }

    private void configureSpaceList() {
        spaceList.getStyleClass().addAll("chat-list", "space-list");
        spaceList.setCellFactory(v -> new ChatCell());
        Label noChannels = new Label("Trayendo los canales…");
        noChannels.getStyleClass().add("empty-label");
        spaceList.setPlaceholder(noChannels);
        ChatMenus.onMenuKey(spaceList, this::rowMenu);
        spaceList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) open(spaceList.getSelectionModel().getSelectedItem());
            if (e.getCode() == KeyCode.ESCAPE || e.getCode() == KeyCode.LEFT) {
                closeSpace();
                e.consume();
            }
        });
        spaceList.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> {
            if (!updating && c != null && spaceList.isFocused() && actsLikeChat(c)) open(c);
        });
        VBox.setVgrow(spaceList, Priority.ALWAYS);
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
        openSpace = null;
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
        if (openSpace == null && foldersOpen()) {
            folderView.focus();
            return;
        }
        if (openSpace != null) {
            spaceList.requestFocus();
            if (spaceList.getSelectionModel().isEmpty() && !spaceItems.isEmpty()) spaceList.getSelectionModel().select(0);
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
        if (openSpace != null && (f.searching() || svc.chat(openSpace) == null)) openSpace = null;
        if (f.searching()) leaveFolders();
        if (foldersOpen() && openSpace == null) folderView.refresh(all); // closeSpace refreshes it
        if (openSpace == null) {
            spaceRows = InboxRows.Rows.EMPTY;
        } else {
            Chat s = svc.chat(openSpace);
            spaceTitle.setText(s.title());
            spaceRows = InboxRows.channels(all, openSpace, f.accountId(), spaces.names());
            spaceList.setAccessibleText("Canales de " + s.title());
        }
        updating = true;
        try {
            items.setAll(visible);
            spaceItems.setAll(spaceRows.rows());
            selectInList();
        } finally {
            updating = false;
        }
        list.refresh();
        spaceList.refresh();
        markSpaceRead.setDisable(spaceItems.stream().noneMatch(c -> actsLikeChat(c) && c.isUnread()));

        String base = switch (f.view()) {
            case INBOX -> f.unreadOnly() ? "No leídos" : "Bandeja";
            case FAVORITES -> "Favoritos";
            case LOW_PRIORITY -> "Prioridad baja";
            case ARCHIVE -> "Archivados";
        };
        newChat.setVisible(!foldersOpen()); // beside the title Carpetas, + would read as a new folder
        title.setText(foldersOpen() ? "Carpetas" : f.network() == null ? base : f.network().displayName());
        refreshAccountRow(f);
        refreshView(f);
        empty.setText(f.searching() ? "Sin resultados para «" + f.query() + "»"
                : f.view() == ChatFilter.View.ARCHIVE ? "No hay chats archivados"
                : f.unreadOnly() ? "¡Todo al día! No tienes mensajes sin leer 🎉"
                : svc.accounts().stream().noneMatch(a -> a.status() == AccountStatus.CONNECTED)
                    ? "Aún no conectaste ninguna red.\nHaz clic en una red de la izquierda (o en +) para iniciar sesión."
                : "No hay chats aquí todavía");

        boolean showFavs = f.view() == ChatFilter.View.INBOX && !f.searching() && !f.unreadOnly() && openSpace == null && !foldersOpen();
        List<Chat> favs = showFavs ? all.stream().filter(c -> c.favorite() && !c.personalSpace() && !c.archived() && !c.bridgeBot()
                && (f.network() == null || c.network() == f.network())).sorted(ChatFilter.ORDER).limit(8).toList() : List.of();
        favorites.getChildren().setAll(favs.stream().map(this::favoriteTile).toList());
        Nodes.show(favorites, !favs.isEmpty());
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
        boolean space = openSpace != null;
        boolean folders = !space && foldersOpen();
        for (Node x : List.of(chipRow, list)) {
            Nodes.show(x, !space && !folders);
        }
        Nodes.show(folderView, folders);
        String backTo = foldersOpen() ? "Volver a la carpeta" : "Volver a la bandeja";
        spaceBack.setAccessibleText(backTo);
        spaceBack.getTooltip().setText(backTo);
        if (space) {
            Nodes.show(accountRow, false);
        }
        Nodes.show(spaceView, space);
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
        accountRow.getChildren().add(accountChip("Todas las cuentas", f.accountId() == null, group,
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
        int si = -1;
        for (int i = 0; i < spaceItems.size(); i++) if (spaceItems.get(i).id().equals(selectedId)) si = i;
        if (si >= 0) spaceList.getSelectionModel().select(si);
        else spaceList.getSelectionModel().clearSelection();
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
        Label name = new Label(firstWord(c.title()));
        name.getStyleClass().add("favorite-name");
        StackPane dot = new StackPane();
        dot.getStyleClass().add("unread-dot");
        dot.setVisible(c.space() ? rows.unread().getOrDefault(c.id(), 0) > 0 : c.isUnread());
        StackPane.setAlignment(dot, Pos.TOP_RIGHT);
        StackPane av = new StackPane(a, dot);
        av.setMaxSize(44, 44);
        VBox tile = new VBox(4, av, name);
        tile.setAlignment(Pos.TOP_CENTER);
        tile.getStyleClass().add("favorite-tile");
        if (c.id().equals(selectedId)) tile.getStyleClass().add("selected");
        tile.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) open(c);
        });
        tile.setFocusTraversable(true);
        tile.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        tile.setAccessibleText("Favorito: " + describe(c, Accounts.forChat(accounts, c)));
        tile.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) open(c);
        });
        tile.setOnContextMenuRequested(e -> menuFor(c).show(this, e.getScreenX(), e.getScreenY()));
        return tile;
    }

    static String describe(Chat c) {
        return describe(c, null);
    }

    /** What a screen reader says for a chat; {@code account} names the login when the network has several. */
    static String describe(Chat c, String account) {
        StringBuilder sb = new StringBuilder(c.title()).append(", ").append(c.network().displayName());
        if (account != null) sb.append(" (").append(account).append(')');
        if (c.group()) sb.append(", grupo");
        if (c.mentionCount() > 0) sb.append(", te mencionaron");
        if (c.unreadCount() > 0) sb.append(", ").append(c.unreadCount()).append(c.unreadCount() == 1 ? " mensaje sin leer" : " mensajes sin leer");
        else if (c.markedUnread()) sb.append(", marcado como no leído");
        if (c.pinned()) sb.append(", fijado");
        if (c.muted()) sb.append(", silenciado");
        if (c.reminderAt() != null) sb.append(", con recordatorio");
        if (!c.typing().isEmpty()) sb.append(". ").append(Formats.typing(c.typing()));
        else if (!c.lastPreview().isEmpty()) sb.append(". ").append(c.lastOutgoing() ? "Tú: " : c.group() && c.lastSenderName() != null
                ? c.lastSenderName() + ": " : "").append(c.lastPreview());
        String stamp = Formats.listStamp(c.lastActivity(), Instant.now(), ZoneId.systemDefault());
        if (!stamp.isEmpty()) sb.append(". ").append(stamp);
        return sb.toString();
    }

    private static String firstWord(String s) {
        String t = s.replaceAll("^[#@]", "").trim();
        int sp = t.indexOf(' ');
        return sp > 0 ? t.substring(0, sp) : t;
    }

    /** Clears the unread state of every channel of the open server. */
    private void markSpaceRead() {
        for (Chat c : List.copyOf(spaceItems)) if (actsLikeChat(c) && c.isUnread()) svc.markRead(c.id());
        spaceList.requestFocus();
    }

    private boolean actsLikeChat(Chat c) {
        return !c.space() && !Spaces.isOffer(c) && !spaceRows.unjoined().contains(c.id());
    }

    /** Shows a server's channels over the list. */
    private void openSpace(String id) {
        // Discord brings a server's channels as people write in them: on opening, ask for all of them (once).
        if (channelsAsked.add(id)) svc.loadAllChannels(id).exceptionally(e -> null);
        openSpace = id;
        arrowedRow = id;
        spaces.ensureNames(id);
        refresh();
        spaceList.requestFocus();
        if (spaceList.getSelectionModel().isEmpty() && !spaceItems.isEmpty()) {
            updating = true;
            spaceList.getSelectionModel().select(0);
            updating = false;
        }
    }

    /** Back to the list, on the server you came from. */
    private void closeSpace() {
        String was = openSpace;
        openSpace = null;
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
        return openSpace != null;
    }

    /** Shows the list of folders. A network filter would leave out part of what a folder's servers count, so it goes. */
    public void showFolders() {
        openSpace = null;
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
        if (openSpace == null && foldersOpen()) folderView.offerRemoval(menu, c);
        return menu;
    }

    /** The menu of a row, if it has one: a channel to join or a server to add aren't chats yet. */
    private ContextMenu rowMenu(Chat c) {
        return c.space() || actsLikeChat(c) ? menuFor(c) : null;
    }

    private void open(Chat c) {
        if (c == null) return;
        if (c.space()) {
            openSpace(c.id());
            return;
        }
        if (Spaces.isOffer(c)) {
            spaces.add(c.network(), Spaces.offerId(c), c.title());
            return;
        }
        if (spaceRows.unjoined().contains(c.id())) {
            spaces.join(c.id(), c.title());
            return;
        }
        selectedId = c.id();
        onOpen.accept(c);
    }

    /** Opens the next/previous visible chat (Alt+↓ / Alt+↑). */
    public void move(int delta, boolean unreadOnly) {
        boolean folder = openSpace == null && foldersOpen();
        List<Chat> items = folder ? folderView.rows() : this.items;
        if (items.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(selectedId)) idx = i;
        for (int step = 1; step <= items.size(); step++) {
            int i = Math.floorMod(idx + delta * step, items.size());
            if (idx < 0) i = delta > 0 ? step - 1 : items.size() - step;
            Chat c = items.get(i);
            if (actsLikeChat(c) && (!unreadOnly || c.isUnread())) {
                open(c);
                if (folder) folderView.reveal(i);
                else list.scrollTo(i);
                return;
            }
        }
    }

    private final class ChatCell extends ListCell<Chat> {
        private final Avatar avatar = new Avatar(44);
        private final Label name = new Label();
        private final Label stamp = new Label();
        private final Label accountTag = new Label();
        private final Label preview = new Label();
        private final Label badge = new Label();
        private final HBox flags = new HBox(4);
        private final Label disclosure = new Label();
        private final HBox root;

        ChatCell() {
            getStyleClass().add("chat-cell");
            name.getStyleClass().add("chat-name");
            stamp.getStyleClass().add("chat-stamp");
            stamp.setMinWidth(Region.USE_PREF_SIZE);
            preview.getStyleClass().add("chat-preview");
            badge.getStyleClass().add("unread-badge");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            flags.setMinWidth(Region.USE_PREF_SIZE);
            flags.setAlignment(Pos.CENTER_RIGHT);
            flags.getStyleClass().add("chat-flags");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            name.setMinWidth(0);
            accountTag.getStyleClass().add("account-tag");
            accountTag.setMinWidth(Region.USE_PREF_SIZE);
            HBox top = new HBox(6, name, grow, accountTag, stamp);
            top.setAlignment(Pos.CENTER_LEFT);
            Region grow2 = new Region();
            HBox.setHgrow(grow2, Priority.ALWAYS);
            preview.setMinWidth(0);
            HBox bottom = new HBox(6, preview, grow2, flags, badge);
            bottom.setAlignment(Pos.CENTER_LEFT);
            VBox text = new VBox(2, top, bottom);
            text.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMinWidth(0);
            disclosure.getStyleClass().add("disclosure");
            disclosure.setMinWidth(Region.USE_PREF_SIZE);
            root = new HBox(12, avatar, text, disclosure);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setMinWidth(0);
            // Size cells to the viewport instead of their content, so no horizontal scrollbar appears.
            setPrefWidth(0);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && getItem() != null) open(getItem());
            });
            setOnContextMenuRequested(e -> {
                // Anchored to the column: rows are redrawn as messages arrive, and a menu closes with its owner.
                ContextMenu menu = getItem() == null ? null : rowMenu(getItem());
                if (menu != null) menu.show(ChatListPane.this, e.getScreenX(), e.getScreenY());
            });
        }

        @Override
        protected void updateItem(Chat c, boolean isEmpty) {
            super.updateItem(c, isEmpty);
            if (isEmpty || c == null) {
                setGraphic(null);
                setAccessibleText(null);
                pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("current"), false);
                return;
            }
            boolean compact = Settings.get().compactList();
            pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("compact"), compact);
            avatar.set(c.title(), c.id(), c.avatarUrl(), svc);
            avatar.setNetwork(c.network(), Settings.get().showNetworkBadges());
            name.setText(c.title());
            String account = filter.get().accountId() == null ? Accounts.forChat(accounts, c) : null;
            // Several accounts on this network: a tiny avatar says which one (full name on hover / screen reader).
            accountTag.setText(account == null ? "" : Formats.accountShort(account));
            accountTag.setStyle(account == null ? "" : "-fx-background-color: " + Avatar.colorFor(account) + ";");
            accountTag.setTooltip(account == null ? null : new javafx.scene.control.Tooltip("Cuenta: " + account));
            Nodes.show(accountTag, account != null);
            stamp.setText(Formats.listStamp(c.lastActivity(), Instant.now(), ZoneId.systemDefault()));
            boolean unread = c.isUnread();
            Nodes.style(name, "unread", unread);
            Nodes.style(stamp, "unread", unread && !c.muted());
            Nodes.style(preview, "typing", !c.typing().isEmpty());
            if (!c.typing().isEmpty()) {
                TypingDots.show(preview, c.group() ? Formats.typing(c.typing()) : "escribiendo…");
            } else {
                String who = c.lastOutgoing() ? "Tú: " : c.group() && c.lastSenderName() != null
                        ? c.lastSenderName().split(" ")[0] + ": " : "";
                TypingDots.show(preview, null); // cells are recycled: this one may have been someone typing
                preview.setText(who + c.lastPreview());
            }
            Nodes.style(preview, "unread", unread);
            flags.getChildren().clear();
            if (c.reminderAt() != null) flags.getChildren().add(Icons.icon(Icons.CLOCK, 13));
            if (c.muted()) flags.getChildren().add(Icons.icon(Icons.BELL_OFF, 13));
            if (c.pinned()) flags.getChildren().add(Icons.icon(Icons.PIN, 13));
            int n = c.unreadCount();
            badge.setText(c.mentionCount() > 0 ? "@" : n > 0 ? Formats.badge(n) : "");
            Nodes.show(badge, unread);
            Nodes.style(badge, "muted", c.muted());
            Nodes.style(badge, "dot", n == 0 && c.mentionCount() == 0);
            boolean offer = Spaces.isOffer(c);
            boolean space = c.space(), unjoined = spaceRows.unjoined().contains(c.id());
            disclosure.setGraphic(space ? Icons.icon(Icons.CHEVRON_RIGHT, 14) : null);
            Nodes.show(disclosure, space);
            Nodes.style(name, "unjoined", unjoined);
            String a11y = describe(c, Accounts.forChat(accounts, c));
            if (offer) {
                boolean busy = spaces.adding(Spaces.offerId(c));
                stamp.setText("");
                TypingDots.show(preview, null);
                preview.setText(busy ? "Agregando…" : "Servidor sin agregar · toca para traer sus canales");
                Nodes.show(badge, false);
                a11y = c.title() + ", servidor de " + c.network().displayName() + " sin agregar, " + (busy ? "Agregando" : "Agregar");
            } else if (space) {
                Chat latest = rows.latest().get(c.id());
                int u = rows.unread().getOrDefault(c.id(), 0);
                stamp.setText(latest == null ? "" : Formats.listStamp(latest.lastActivity(), Instant.now(), ZoneId.systemDefault()));
                TypingDots.show(preview, null);
                preview.setText(latest == null ? "Sin mensajes" : latest.title() + ": " + latest.lastPreview());
                boolean un = u > 0;
                Nodes.style(name, "unread", un);
                Nodes.style(stamp, "unread", un && !c.muted());
                Nodes.style(preview, "unread", un);
                badge.setText(Formats.badge(u));
                Nodes.show(badge, un);
                Nodes.style(badge, "dot", false);
                a11y = c.title() + ", " + (c.network() == app.unichat.model.Network.DISCORD ? "servidor" : "grupo") + " de "
                        + c.network().displayName() + (un ? ", " + u + " sin leer" : "") + (c.muted() ? ", silenciado" : "")
                        + (latest == null ? "" : ". " + latest.title() + ": " + latest.lastPreview()) + ". Abre sus canales";
            } else if (unjoined) {
                stamp.setText("");
                TypingDots.show(preview, null);
                preview.setText("Toca para unirte");
                Nodes.show(badge, false);
                a11y = c.title() + ", canal sin unirte, Unirse";
            }
            setGraphic(root);
            setAccessibleText(a11y);
            setPadding(compact ? new Insets(4, 10, 4, 10) : new Insets(8, 10, 8, 10));
            pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("current"), Objects.equals(c.id(), selectedId));
        }
    }
}

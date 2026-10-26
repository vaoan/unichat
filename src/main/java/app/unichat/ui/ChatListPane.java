package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
    private final Consumer<Chat> onOpen;
    private String selectedId;
    private boolean updating;

    public ChatListPane(ChatService svc, Consumer<Chat> onOpen) {
        this.svc = svc;
        this.onOpen = onOpen;
        getStyleClass().add("chat-list-pane");

        title.getStyleClass().add("pane-title");
        HBox header = new HBox(title);
        header.getStyleClass().add("chat-list-header");
        header.setAlignment(Pos.CENTER_LEFT);

        search.setPromptText("Buscar   " + (MainView.MAC ? "⌘K" : "Ctrl+K"));
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

        FlowPane chipRow = new FlowPane(6, 6,
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

        list.getStyleClass().add("chat-list");
        list.setAccessibleText("Chats");
        list.setCellFactory(v -> new ChatCell());
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) open(list.getSelectionModel().getSelectedItem());
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> {
            if (!updating && c != null && list.isFocused()) open(c);
        });
        empty.getStyleClass().add("empty-label");
        empty.setWrapText(true);
        list.setPlaceholder(empty);
        VBox.setVgrow(list, Priority.ALWAYS);

        accountRow.getStyleClass().addAll("chip-row", "account-row");
        accountRow.setAccessibleText("Cuentas");
        getChildren().addAll(header, searchBox, chipRow, accountRow, favorites, list);
        filter.addListener((o, a, b) -> refresh());
        refresh();
    }

    private ToggleButton chip(String text, java.util.function.UnaryOperator<ChatFilter> change) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("chip");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setToggleGroup(chips);
        b.setOnAction(e -> filter.set(change.apply(filter.get())));
        return b;
    }

    public ObjectProperty<ChatFilter> filterProperty() { return filter; }

    public void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    /** Focuses the chat list (F6 cycle), keeping the open chat selected. */
    public void focusList() {
        list.requestFocus();
        if (list.getSelectionModel().isEmpty() && !items.isEmpty()) list.getSelectionModel().select(0);
    }

    public void setSelected(String chatId) {
        selectedId = chatId;
        selectInList();
    }

    /** Rebuilds the visible list from the service; call on the FX thread after chatsChanged. */
    public void refresh() {
        ChatFilter f = filter.get();
        List<Chat> all = svc.chats();
        List<Chat> visible = f.apply(all);
        updating = true;
        try {
            items.setAll(visible);
            selectInList();
        } finally {
            updating = false;
        }
        list.refresh();

        String base = switch (f.view()) {
            case INBOX -> f.unreadOnly() ? "No leídos" : "Bandeja";
            case FAVORITES -> "Favoritos";
            case LOW_PRIORITY -> "Prioridad baja";
            case ARCHIVE -> "Archivados";
        };
        title.setText(f.network() == null ? base : f.network().displayName());
        refreshAccountRow(f);
        empty.setText(f.searching() ? "Sin resultados para «" + f.query() + "»"
                : f.view() == ChatFilter.View.ARCHIVE ? "No hay chats archivados"
                : f.unreadOnly() ? "¡Todo al día! No tienes mensajes sin leer 🎉"
                : svc.accounts().stream().noneMatch(a -> a.status() == app.unichat.model.AccountStatus.CONNECTED)
                    ? "Aún no conectaste ninguna red.\nHaz clic en una red de la izquierda (o en +) para iniciar sesión."
                : "No hay chats aquí todavía");

        boolean showFavs = f.view() == ChatFilter.View.INBOX && !f.searching() && !f.unreadOnly();
        List<Chat> favs = showFavs ? all.stream().filter(c -> c.favorite() && !c.archived() && !c.bridgeBot()
                && (f.network() == null || c.network() == f.network())).sorted(ChatFilter.ORDER).limit(8).toList() : List.of();
        favorites.getChildren().setAll(favs.stream().map(this::favoriteTile).toList());
        favorites.setVisible(!favs.isEmpty());
        favorites.setManaged(!favs.isEmpty());
    }

    /** "Todas · Personal · Trabajo" chips, shown only for a network with several accounts. */
    private void refreshAccountRow(ChatFilter f) {
        List<app.unichat.model.Account> accounts = f.network() == null ? List.of() : Accounts.of(svc, f.network());
        // Only offer per-account filters when every chat of the network knows its account; otherwise a filter
        // would silently hide chats (Matrix bridges don't always say which login a portal belongs to).
        boolean attributed = f.network() != null && svc.chats().stream()
                .filter(c -> c.network() == f.network() && !c.bridgeBot()).allMatch(c -> c.accountId() != null);
        boolean show = accounts.size() > 1 && !f.searching() && attributed;
        accountRow.setVisible(show);
        accountRow.setManaged(show);
        accountRow.getChildren().clear();
        if (!show) return;
        ToggleGroup group = new ToggleGroup();
        accountRow.getChildren().add(accountChip("Todas las cuentas", f.accountId() == null, group,
                () -> filter.set(filter.get().withAccount(f.network(), null))));
        for (app.unichat.model.Account a : accounts) {
            accountRow.getChildren().add(accountChip(Accounts.name(a), a.id().equals(f.accountId()), group,
                    () -> filter.set(filter.get().withAccount(f.network(), a.id()))));
        }
    }

    private ToggleButton accountChip(String text, boolean selected, ToggleGroup group, Runnable action) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().addAll("chip", "account-chip");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setToggleGroup(group);
        b.setSelected(selected);
        b.setOnAction(e -> {
            if (!b.isSelected()) b.setSelected(true);
            action.run();
        });
        return b;
    }

    private void selectInList() {
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
        dot.setVisible(c.isUnread());
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
        tile.setAccessibleText("Favorito: " + describe(c, Accounts.forChat(svc, c)));
        tile.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) open(c);
        });
        tile.setOnContextMenuRequested(e -> ChatMenus.forChat(c, svc, null).show(tile, e.getScreenX(), e.getScreenY()));
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

    private void open(Chat c) {
        if (c == null) return;
        selectedId = c.id();
        onOpen.accept(c);
    }

    /** Opens the next/previous visible chat (Alt+↓ / Alt+↑). */
    public void move(int delta, boolean unreadOnly) {
        if (items.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(selectedId)) idx = i;
        for (int step = 1; step <= items.size(); step++) {
            int i = Math.floorMod(idx + delta * step, items.size());
            if (idx < 0) i = delta > 0 ? step - 1 : items.size() - step;
            Chat c = items.get(i);
            if (!unreadOnly || c.isUnread()) {
                open(c);
                list.scrollTo(i);
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
            top.setAlignment(Pos.BASELINE_LEFT);
            Region grow2 = new Region();
            HBox.setHgrow(grow2, Priority.ALWAYS);
            preview.setMinWidth(0);
            HBox bottom = new HBox(6, preview, grow2, flags, badge);
            bottom.setAlignment(Pos.CENTER_LEFT);
            VBox text = new VBox(2, top, bottom);
            text.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMinWidth(0);
            root = new HBox(12, avatar, text);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setMinWidth(0);
            // Size cells to the viewport instead of their content, so no horizontal scrollbar appears.
            setPrefWidth(0);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && getItem() != null) open(getItem());
            });
            setOnContextMenuRequested(e -> {
                if (getItem() != null) ChatMenus.forChat(getItem(), svc, null).show(this, e.getScreenX(), e.getScreenY());
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
            String account = filter.get().accountId() == null ? Accounts.forChat(svc, c) : null;
            accountTag.setText(account == null ? "" : account);
            accountTag.setVisible(account != null);
            accountTag.setManaged(account != null);
            stamp.setText(Formats.listStamp(c.lastActivity(), Instant.now(), ZoneId.systemDefault()));
            boolean unread = c.isUnread();
            toggle(name, "unread", unread);
            toggle(stamp, "unread", unread && !c.muted());
            toggle(preview, "typing", !c.typing().isEmpty());
            if (!c.typing().isEmpty()) {
                preview.setText(c.group() ? Formats.typing(c.typing()) : "escribiendo…");
            } else {
                String who = c.lastOutgoing() ? "Tú: " : c.group() && c.lastSenderName() != null
                        ? c.lastSenderName().split(" ")[0] + ": " : "";
                preview.setText(who + c.lastPreview());
            }
            toggle(preview, "unread", unread);
            flags.getChildren().clear();
            if (c.reminderAt() != null) flags.getChildren().add(Icons.icon(Icons.CLOCK, 13));
            if (c.muted()) flags.getChildren().add(Icons.icon(Icons.BELL_OFF, 13));
            if (c.pinned()) flags.getChildren().add(Icons.icon(Icons.PIN, 13));
            int n = c.unreadCount();
            badge.setText(c.mentionCount() > 0 ? "@" : n > 0 ? (n > 99 ? "99+" : Integer.toString(n)) : "");
            badge.setVisible(unread);
            badge.setManaged(unread);
            toggle(badge, "muted", c.muted());
            toggle(badge, "dot", n == 0 && c.mentionCount() == 0);
            setGraphic(root);
            setAccessibleText(describe(c, Accounts.forChat(svc, c)));
            setPadding(compact ? new Insets(4, 10, 4, 10) : new Insets(8, 10, 8, 10));
            pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("current"), Objects.equals(c.id(), selectedId));
        }

        private static void toggle(Node n, String cls, boolean on) {
            n.getStyleClass().remove(cls);
            if (on) n.getStyleClass().add(cls);
        }
    }
}

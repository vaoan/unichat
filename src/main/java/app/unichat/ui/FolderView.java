package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.service.ChatFolders;
import app.unichat.service.Folder;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The chat column while in folders, like Discord's servers or Telegram's folders: the list of names and, on clicking
 * one, the list is replaced by its chats (not an accordion).
 */
final class FolderView extends VBox {
    private final ChatFolders folders;
    private final Consumer<Chat> open;
    private final ObservableList<Folder> names = FXCollections.observableArrayList();
    private final ListView<Folder> nameList = new ListView<>(names);
    private final ObservableList<Chat> chats = FXCollections.observableArrayList();
    private final ListView<Chat> chatList = new ListView<>(chats);
    private final Label folderTitle = new Label();
    private final Button create;
    private final VBox namesView;
    private final VBox insideView;
    private final Map<String, Long> unread = new HashMap<>();
    /** Per folder, its chat with the latest activity (null: empty) and how many chats it holds, for its row. */
    private final Map<String, Chat> latest = new HashMap<>();
    private final Map<String, Integer> sizes = new HashMap<>();
    private final FolderDialogs dialogs;
    private Runnable onLeave;
    /** The folder whose chats are showing, or null for the names. */
    private String openId;
    private String selectedChatId;
    private boolean updating;
    private List<Chat> all = List.of();

    FolderView(ChatFolders folders, FolderDialogs dialogs, Supplier<ListCell<Chat>> cells, Consumer<Chat> open) {
        this.folders = folders;
        this.dialogs = dialogs;
        this.open = open;
        getStyleClass().add("folder-view");

        create = new Button(I18n.t("folders.new"));
        create.setGraphic(Icons.icon(Icons.PLUS, 16));
        create.getStyleClass().addAll("secondary-button", "folder-new");
        create.setMaxWidth(Double.MAX_VALUE);
        create.setOnAction(e -> dialogs.create(this::focus));
        configureNames();
        namesView = new VBox(8, create, nameList);
        namesView.getStyleClass().add("folder-names");
        // A filter: the list would take Esc for itself. Left stays with the list, inside a folder it means back.
        namesView.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && onLeave != null) {
                onLeave.run();
                e.consume();
            }
        });

        Button back = Icons.iconButton(Icons.CHEVRON_LEFT, I18n.t("folders.back"), this::showNames);
        folderTitle.getStyleClass().addAll("space-title", "folder-title");
        folderTitle.setMinWidth(0);
        folderTitle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(folderTitle, Priority.ALWAYS);
        HBox header = new HBox(8, back, folderTitle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("space-header");
        configureChats(cells);
        insideView = new VBox(6, header, chatList);

        for (VBox v : List.of(namesView, insideView)) VBox.setVgrow(v, Priority.ALWAYS);
        getChildren().addAll(namesView, insideView);
        showCurrent();
    }


    /** Esc on the names: out of folders. */
    void setOnLeave(Runnable onLeave) { this.onLeave = onLeave; }

    ListView<Chat> chatList() { return chatList; }

    private void configureNames() {
        nameList.getStyleClass().addAll("chat-list", "folder-list");
        nameList.setAccessibleText(I18n.t("folders.title"));
        nameList.setAccessibleHelp(I18n.t("folders.listHelp"));
        nameList.setCellFactory(v -> new FolderCell());
        Label none = new Label(I18n.t("folders.none"));
        none.getStyleClass().add("empty-label");
        none.setWrapText(true);
        nameList.setPlaceholder(none);
        nameList.setOnKeyPressed(e -> {
            Folder f = nameList.getSelectionModel().getSelectedItem();
            if (f == null) return;
            switch (e.getCode()) {
                case ENTER, RIGHT -> openFolder(f.id());
                case F2 -> dialogs.rename(f, this::focus);
                case DELETE, BACK_SPACE -> dialogs.delete(f, this::focus);
                default -> { return; }
            }
            e.consume();
        });
        ChatMenus.onMenuKey(nameList, this::folderMenu);
        VBox.setVgrow(nameList, Priority.ALWAYS);
    }

    private void configureChats(Supplier<ListCell<Chat>> cells) {
        chatList.getStyleClass().addAll("chat-list", "folder-chats");
        chatList.setCellFactory(v -> cells.get());
        Label none = new Label(I18n.t("folders.empty"));
        none.getStyleClass().add("empty-label");
        none.setWrapText(true);
        chatList.setPlaceholder(none);
        chatList.setOnKeyPressed(e -> {
            Chat sel = chatList.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER || (e.getCode() == KeyCode.RIGHT && sel != null && sel.space())) {
                if (sel != null) open.accept(sel);
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE || e.getCode() == KeyCode.LEFT) {
                showNames();
                e.consume();
            }
        });
        // Arrowing onto a chat opens it; a server waits for Enter or a click, as in the inbox.
        chatList.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> {
            // After the list has finished selecting (see ChatListPane).
            if (!updating && c != null && chatList.isFocused() && !c.space()) Platform.runLater(() -> {
                if (chatList.getSelectionModel().getSelectedItem() == c) open.accept(c);
            });
        });
        VBox.setVgrow(chatList, Priority.ALWAYS);
    }

    /** Back to the names, on the folder you came from. */
    void showNames() {
        String was = openId;
        openId = null;
        refresh(all);
        selectFolder(was);
        nameList.scrollTo(Math.max(0, nameList.getSelectionModel().getSelectedIndex()));
        focus();
    }

    void openFolder(String id) {
        openId = id;
        refresh(all);
        focus();
        // After the layout: the list was hidden and empty until now, so it can't yet tell how far a row is.
        Platform.runLater(() -> reveal(Math.max(0, chatList.getSelectionModel().getSelectedIndex())));
    }

    /** Back from a server: on its row, though a channel of it is what's open. */
    void focusOn(String chatId) {
        updating = true;
        try {
            selectChat(chats.stream().anyMatch(c -> c.id().equals(chatId)) ? chatId : selectedChatId);
        } finally {
            updating = false;
        }
        focus();
    }

    /** Opens the folder called {@code name}, if any (snapshot diagnostics). */
    void openNamed(String name) {
        folders.folders().stream().filter(f -> f.name().equals(name)).findFirst().ifPresent(f -> openFolder(f.id()));
    }

    /** Leaves whatever folder was open, without touching the focus: the view is about to hide or to fill up. */
    void reset() {
        openId = null;
        showCurrent();
    }

    /** Redraws from the service; the open folder may have just been deleted. */
    void refresh(List<Chat> allChats) {
        all = allChats;
        List<Folder> now = folders.folders();
        Folder current = now.stream().filter(f -> f.id().equals(openId)).findFirst().orElse(null);
        boolean lost = openId != null && current == null;
        boolean hadFocus = chatList.isFocused();
        if (lost) openId = null;
        // Focus on a button doesn't read the list's placeholder, so the button says it.
        create.setAccessibleHelp(now.isEmpty() ? I18n.t("folders.none") : null);
        unread.clear();
        FolderRows.Servers servers = new FolderRows.Servers(all);
        latest.clear();
        sizes.clear();
        if (current == null) {
            for (Folder f : now) {
                unread.put(f.id(), FolderRows.unread(f, servers));
                List<Chat> in = FolderRows.chatsIn(f, servers);
                sizes.put(f.id(), in.size());
                Chat newest = FolderRows.latest(in);
                if (newest != null) latest.put(f.id(), newest);
            }
        }

        updating = true;
        try {
            Folder selected = nameList.getSelectionModel().getSelectedItem();
            if (!names.equals(now)) {
                names.setAll(now);
                selectFolder(selected == null ? null : selected.id());
            }
            List<Chat> rows = current == null ? List.of() : FolderRows.chatsIn(current, servers);
            Chat keep = chatList.getSelectionModel().getSelectedItem();
            if (!chats.equals(rows)) {
                chats.setAll(rows);
                selectChat(keep == null ? selectedChatId : keep.id());
            }
        } finally {
            updating = false;
        }
        if (current != null) {
            folderTitle.setText(current.name());
            chatList.setAccessibleText(I18n.t("folders.chatsOf", current.name()));
        }
        ListRows.redraw(nameList);
        ListRows.redraw(chatList);
        showCurrent();
        if (lost && hadFocus) focus();
    }

    /** The open chat changed: mark it here too. */
    void select(String chatId) {
        selectedChatId = chatId;
        updating = true;
        try {
            selectChat(chatId);
        } finally {
            updating = false;
        }
    }

    private void selectChat(String id) {
        for (int i = 0; i < chats.size(); i++) {
            if (chats.get(i).id().equals(id)) {
                chatList.getSelectionModel().select(i);
                return;
            }
        }
        chatList.getSelectionModel().clearSelection();
    }

    private void selectFolder(String id) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).id().equals(id)) {
                nameList.getSelectionModel().select(i);
                return;
            }
        }
    }

    void focus() {
        if (openId == null && names.isEmpty()) {
            create.requestFocus();
            return;
        }
        ListView<?> shown = openId == null ? nameList : chatList;
        shown.requestFocus();
        if (shown.getSelectionModel().isEmpty() && !shown.getItems().isEmpty()) {
            updating = true;
            shown.getSelectionModel().select(0);
            updating = false;
        }
    }

    /** The chats on show, for moving through them with Alt+arrows; none while on the names. */
    List<Chat> rows() { return chats; }

    void reveal(int row) { chatList.scrollTo(row); }

    /** Inside a folder, a chat's menu can also take it out, right after the Carpetas submenu. */
    void offerRemoval(ContextMenu menu, Chat c) {
        Folder current = folders.folders().stream().filter(f -> f.id().equals(openId)).findFirst().orElse(null);
        if (current == null || !current.chatKeys().contains(c.prefKey())) return;
        int at = 0;
        for (int i = 0; i < menu.getItems().size(); i++) if (menu.getItems().get(i) instanceof Menu) at = i + 1;
        menu.getItems().add(at, ChatMenus.item(I18n.t("folders.removeFromThis"), () -> folders.setInFolder(current.id(), c, false)));
    }

    private void showCurrent() {
        boolean inside = openId != null;
        Nodes.show(namesView, !inside);
        Nodes.show(insideView, inside);
    }

    private ContextMenu folderMenu(Folder f) {
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                ChatMenus.item(I18n.t("folders.rename"), () -> dialogs.rename(f, this::focus)),
                new SeparatorMenuItem(),
                ChatMenus.danger(ChatMenus.item(I18n.t("folders.delete"), () -> dialogs.delete(f, this::focus))));
        return menu;
    }

    /** A folder's row, as big as a chat's: its tile, name and time, and the last message in it. */
    private final class FolderCell extends ListCell<Folder> implements ListRows.Redrawable {
        @Override public void redraw() {
            if (getItem() != null && !isEmpty()) updateItem(getItem(), false);
        }

        private static final double TILE = 44;
        private final Label name = new Label();
        private final Label stamp = new Label();
        private final Label preview = new Label();
        private final Label badge = new Label();
        private final StackPane tile = new StackPane(Icons.icon(Icons.FOLDER, 22));
        private final HBox root;

        FolderCell() {
            getStyleClass().add("chat-cell");
            name.getStyleClass().add("chat-name");
            name.setMinWidth(0);
            stamp.getStyleClass().add("chat-stamp");
            stamp.setMinWidth(Region.USE_PREF_SIZE);
            preview.getStyleClass().add("chat-preview");
            preview.setMinWidth(0);
            badge.getStyleClass().add("unread-badge");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            tile.getStyleClass().add("folder-tile");
            tile.setMinSize(TILE, TILE);
            tile.setPrefSize(TILE, TILE);
            tile.setMaxSize(TILE, TILE);
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox top = new HBox(6, name, grow, stamp);
            top.setAlignment(Pos.CENTER_LEFT);
            Region grow2 = new Region();
            HBox.setHgrow(grow2, Priority.ALWAYS);
            HBox bottom = new HBox(6, preview, grow2, badge);
            bottom.setAlignment(Pos.CENTER_LEFT);
            VBox text = new VBox(2, top, bottom);
            text.setAlignment(Pos.CENTER_LEFT);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            root = new HBox(12, tile, text);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setMinWidth(0);
            // Size cells to the viewport instead of their content, so no horizontal scrollbar appears.
            setPrefWidth(0);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && getItem() != null) openFolder(getItem().id());
            });
            setOnContextMenuRequested(e -> {
                if (getItem() != null) folderMenu(getItem()).show(FolderView.this, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }

        @Override
        protected void updateItem(Folder f, boolean isEmpty) {
            super.updateItem(f, isEmpty);
            if (isEmpty || f == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            long n = Objects.requireNonNullElse(unread.get(f.id()), 0L);
            Chat newest = latest.get(f.id());
            int size = Objects.requireNonNullElse(sizes.get(f.id()), 0);
            name.setText(f.name());
            Nodes.style(name, "unread", n > 0);
            tile.setStyle("-fx-background-color: " + Avatar.colorFor(f.name()) + ";");
            stamp.setText(newest == null || newest.lastActivity() == null ? ""
                    : Formats.listStamp(newest.lastActivity(), java.time.Instant.now(), java.time.ZoneId.systemDefault()));
            preview.setText(newest == null ? I18n.t("folders.emptyRow") : lastLine(newest));
            Nodes.style(preview, "unread", n > 0);
            badge.setText(Formats.badge(n));
            Nodes.show(badge, n > 0);
            // The chats' own spacing (ChatRowCell), compact list included.
            setPadding(app.unichat.settings.Settings.get().compactList() ? new Insets(4, 10, 4, 10) : new Insets(8, 10, 8, 10));
            setGraphic(root);
            setAccessibleText(f.name() + ", " + I18n.n("folders.chatCount", size) + (n == 0 ? "" : ", " + I18n.n("rail.unread", n))
                    + (newest == null ? "" : ", " + lastLine(newest)));
        }

        /** "Family: Who's bringing what?" — whose chat and what was last said there. */
        private static String lastLine(Chat c) {
            String p = c.lastPreview() == null ? "" : c.lastPreview();
            return p.isEmpty() ? c.title() : c.title() + ": " + p;
        }
    }
}

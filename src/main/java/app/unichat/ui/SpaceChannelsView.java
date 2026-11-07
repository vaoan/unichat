package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.service.ChatService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** A server's channels, which slide over the chat list like a Telegram forum's topics. */
final class SpaceChannelsView extends VBox {
    private final ChatService svc;
    private final Spaces spaces;
    private final Consumer<Chat> open;
    private final ObservableList<Chat> items = FXCollections.observableArrayList();
    private final ListView<Chat> list = new ListView<>(items);
    private final Label title = new Label();
    private final Button markRead = Icons.iconButton(Icons.CHECK_DOUBLE, "Marcar todo como leído", this::markRead);
    private final Button back;
    private final Set<String> channelsAsked = new HashSet<>();
    /** The server showing, or null. */
    private String openId;
    private InboxRows.Rows rows = InboxRows.Rows.EMPTY;
    private boolean updating;

    SpaceChannelsView(ChatService svc, Spaces spaces, Supplier<ListCell<Chat>> cells, Consumer<Chat> open,
                      Function<Chat, ContextMenu> rowMenu, Runnable onBack) {
        this.svc = svc;
        this.spaces = spaces;
        this.open = open;
        back = Icons.iconButton(Icons.CHEVRON_LEFT, "Volver a la bandeja", onBack);
        title.getStyleClass().add("space-title");
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        HBox header = new HBox(8, back, title, markRead);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("space-header");
        configureList(cells, rowMenu, onBack);
        setSpacing(6);
        getChildren().addAll(header, list);
        getStyleClass().add("space-view");
        VBox.setVgrow(this, Priority.ALWAYS);
        Nodes.show(this, false);
    }

    private void configureList(Supplier<ListCell<Chat>> cells, Function<Chat, ContextMenu> rowMenu, Runnable onBack) {
        list.getStyleClass().addAll("chat-list", "space-list");
        list.setCellFactory(v -> cells.get());
        Label noChannels = new Label("Trayendo los canales…");
        noChannels.getStyleClass().add("empty-label");
        list.setPlaceholder(noChannels);
        ChatMenus.onMenuKey(list, rowMenu);
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) open.accept(list.getSelectionModel().getSelectedItem());
            if (e.getCode() == KeyCode.ESCAPE || e.getCode() == KeyCode.LEFT) {
                onBack.run();
                e.consume();
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> {
            if (!updating && c != null && list.isFocused() && actsLikeChat(c)) open.accept(c);
        });
        VBox.setVgrow(list, Priority.ALWAYS);
    }

    String openId() { return openId; }

    boolean isOpen() { return openId != null; }

    /** Shows server {@code id}; call {@link #refresh} next. */
    void open(String id) {
        // Discord brings a server's channels as people write in them: on opening, ask for all of them (once).
        if (channelsAsked.add(id)) svc.loadAllChannels(id).exceptionally(e -> null);
        openId = id;
        spaces.ensureNames(id);
    }

    /** Back to whatever it covers; call {@link #refresh} next. */
    void close() {
        openId = null;
    }

    boolean isUnjoined(Chat c) {
        return rows.unjoined().contains(c.id());
    }

    boolean actsLikeChat(Chat c) {
        return !c.space() && !Spaces.isOffer(c) && !isUnjoined(c);
    }

    /** Reads the open server's channels from {@code all}, for {@link #showRows} to put on screen. */
    void refresh(List<Chat> all, ChatFilter f) {
        if (openId == null) {
            rows = InboxRows.Rows.EMPTY;
        } else {
            Chat s = svc.chat(openId);
            title.setText(s.title());
            rows = InboxRows.channels(all, openId, f.accountId(), spaces.names());
            list.setAccessibleText("Canales de " + s.title());
        }
    }

    /** Puts the rows in the list and runs {@code select}: what a redraw selects isn't the person opening a channel. */
    void showRows(Runnable select) {
        updating = true;
        try {
            items.setAll(rows.rows());
            select.run();
        } finally {
            updating = false;
        }
    }

    void redraw() {
        list.refresh();
        markRead.setDisable(items.stream().noneMatch(c -> actsLikeChat(c) && c.isUnread()));
    }

    void setBackText(String text) {
        back.setAccessibleText(text);
        back.getTooltip().setText(text);
    }

    void select(String chatId) {
        int si = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(chatId)) si = i;
        if (si >= 0) list.getSelectionModel().select(si);
        else list.getSelectionModel().clearSelection();
    }

    /** Keyboard into the list, keeping the open chat selected. */
    void focusList() {
        list.requestFocus();
        if (list.getSelectionModel().isEmpty() && !items.isEmpty()) list.getSelectionModel().select(0);
    }

    /** Keyboard into the list on opening: a first row selected only to have somewhere to start, not opened. */
    void focusOnOpening() {
        list.requestFocus();
        if (list.getSelectionModel().isEmpty() && !items.isEmpty()) {
            updating = true;
            list.getSelectionModel().select(0);
            updating = false;
        }
    }

    /** Clears the unread state of every channel of the open server. */
    private void markRead() {
        for (Chat c : List.copyOf(items)) if (actsLikeChat(c) && c.isUnread()) svc.markRead(c.id());
        list.requestFocus();
    }
}

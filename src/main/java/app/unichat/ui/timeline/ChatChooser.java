package app.unichat.ui.timeline;

import app.unichat.model.Chat;
import app.unichat.service.ChatService;
import app.unichat.support.I18n;
import app.unichat.ui.SearchFields;
import app.unichat.ui.chats.ChatFilter;
import app.unichat.ui.chats.ChatLine;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Choosing chats to send to, of any network and as many as wanted: a search (the ⌘K's), the chats one can write in, a
 * check each (a click, Space or Enter), and how many are chosen. For forwarding and for sending a capture.
 */
final class ChatChooser {
    private static final double LIST_HEIGHT = 260;

    private final ChatService svc;
    private final Set<String> chosen = new LinkedHashSet<>();
    private final TextField search = new TextField();
    private final ListView<Chat> list;
    private final Label count = new Label();
    private final Consumer<List<String>> changed;

    /** {@code changed}: told what's chosen whenever it changes (a send button follows it). */
    ChatChooser(ChatService svc, Consumer<List<String>> changed) {
        this.svc = svc;
        this.changed = changed;
        search.setPromptText(I18n.t("forward.search"));
        search.setAccessibleText(I18n.t("forward.search"));
        ObservableList<Chat> shown = FXCollections.observableArrayList(ChatFilter.writable(svc.chats(), "", false));
        list = new ListView<>(shown);
        list.getStyleClass().add("forward-chats");
        list.setPrefHeight(LIST_HEIGHT);
        count.getStyleClass().add("settings-help");
        Consumer<Chat> toggle = c -> {
            if (c == null) return;
            if (!chosen.remove(c.id())) chosen.add(c.id());
            sync();
        };
        list.setCellFactory(v -> new ChatCell(toggle));
        list.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) {
                toggle.accept(list.getSelectionModel().getSelectedItem());
                e.consume();
            }
        });
        search.textProperty().addListener((o, a, q) -> {
            shown.setAll(ChatFilter.writable(svc.chats(), q, false));
            if (!shown.isEmpty()) list.getSelectionModel().select(0);
        });
        search.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN) {
                list.requestFocus();
                if (list.getSelectionModel().isEmpty()) list.getSelectionModel().select(0);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && !search.getText().isBlank()) {
                toggle.accept(list.getSelectionModel().getSelectedItem());
                e.consume();
            }
        });
        sync();
    }

    Node search() { return SearchFields.clearable(search); }

    Node list() { return list; }

    Label count() { return count; }

    /** The chats chosen, in the order they were. */
    List<String> chosen() { return List.copyOf(chosen); }

    void focus() { search.requestFocus(); }

    private void sync() {
        count.setText(chosen.isEmpty() ? I18n.t("forward.noneChosen") : I18n.n("forward.chosen", chosen.size()));
        for (Node n : list.lookupAll(".list-cell")) if (n instanceof ChatCell cell) cell.sync();
        changed.accept(chosen());
    }

    /** A chat to choose: its check, picture, name and network. */
    private final class ChatCell extends ListCell<Chat> {
        private final CheckBox check = new CheckBox();
        private final ChatLine line = new ChatLine(svc);
        private final HBox row;

        ChatCell(Consumer<Chat> toggle) {
            check.setFocusTraversable(false);
            check.setOnAction(e -> toggle.accept(getItem()));
            HBox.setHgrow(line, Priority.ALWAYS);
            row = new HBox(10, check, line);
            row.setAlignment(Pos.CENTER_LEFT);
            setOnMouseClicked(e -> {
                if (getItem() != null && e.getClickCount() == 1 && e.getTarget() != check && !check.isHover()) toggle.accept(getItem());
            });
        }

        @Override
        protected void updateItem(Chat c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            line.show(c, c.network().displayName());
            sync();
            setGraphic(row);
        }

        /** Its check, and what a screen reader says, as chosen now. */
        void sync() {
            Chat c = getItem();
            if (c == null) return;
            boolean on = chosen.contains(c.id());
            check.setSelected(on);
            setAccessibleText(I18n.t(on ? "forward.chatChosen" : "forward.chatNotChosen", c.title(), c.network().displayName()));
        }
    }
}

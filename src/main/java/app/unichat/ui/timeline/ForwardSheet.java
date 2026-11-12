package app.unichat.ui.timeline;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.service.ChatService;
import app.unichat.service.Forwarding;
import app.unichat.support.I18n;
import app.unichat.ui.SearchFields;
import app.unichat.ui.chats.ChatLine;
import app.unichat.ui.chats.ChatFilter;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * "Reenviar": to which chats, of any network and as many as wanted, and what the copies say about where they came from
 * (who, the chat, when: {@link ForwardNote}), starting from Ajustes' choice, with the line itself shown as it will go.
 * A sheet in the window's overlay, like the rest of the app's questions.
 */
public final class ForwardSheet {
    private static final double WIDTH = 460, LIST_HEIGHT = 260;

    private final ChatService svc;
    private final Consumer<Node> show;
    private final Runnable hide;
    private final Consumer<String> toast;

    public ForwardSheet(ChatService svc, Consumer<Node> show, Runnable hide, Consumer<String> toast) {
        this.svc = svc;
        this.show = show;
        this.hide = hide;
        this.toast = toast;
    }

    /**
     * Asks where {@code messages} (in the order they go) of {@code from} go; {@code sent} runs when they start going,
     * {@code after} once the sheet closes, however.
     */
    public void open(List<Message> messages, Chat from, Runnable sent, Runnable after) {
        if (messages.isEmpty()) return;
        Set<String> chosen = new LinkedHashSet<>();
        Label title = new Label(I18n.n("forward.title", messages.size()));
        title.getStyleClass().add("sheet-title");

        TextField search = new TextField();
        search.setPromptText(I18n.t("forward.search"));
        search.setAccessibleText(I18n.t("forward.search"));
        ObservableList<Chat> shown = FXCollections.observableArrayList(ChatFilter.writable(svc.chats(), "", false));
        ListView<Chat> list = new ListView<>(shown);
        list.getStyleClass().add("forward-chats");
        list.setPrefHeight(LIST_HEIGHT);
        Label count = new Label();
        count.getStyleClass().add("settings-help");
        Button go = new Button(I18n.t("forward.send"));
        Runnable changed = () -> {
            count.setText(chosen.isEmpty() ? I18n.t("forward.noneChosen") : I18n.n("forward.chosen", chosen.size()));
            go.setDisable(chosen.isEmpty());
            for (Node n : list.lookupAll(".list-cell")) if (n instanceof ChatCell cell) cell.sync();
        };
        Consumer<Chat> toggle = c -> {
            if (c == null) return;
            if (!chosen.remove(c.id())) chosen.add(c.id());
            changed.run();
        };
        list.setCellFactory(v -> new ChatCell(chosen, toggle));
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

        Saying saying = new Saying(messages.getFirst(), from);

        Button cancel = new Button(I18n.t("folders.dialog.cancel"));
        cancel.getStyleClass().add("secondary-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> hide.run());
        go.getStyleClass().add("primary-button");
        go.setDefaultButton(true);
        go.setOnAction(e -> {
            ForwardNote note = saying.note();
            List<String> to = List.copyOf(chosen);
            hide.run();
            send(messages, from, to, note);
            sent.run();
        });
        changed.run();
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, count, grow, cancel, go);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(12, title, SearchFields.clearable(search), list, saying.label, saying.boxes, saying.preview, buttons);
        card.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && e.isShortcutDown() && !go.isDisabled()) {
                go.fire(); // ⌘Enter sends from anywhere in the sheet; Enter alone chooses a chat
                e.consume();
            }
        });
        card.getStyleClass().addAll("card", "folder-dialog", "forward-sheet");
        card.setPrefWidth(WIDTH);
        card.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) Platform.runLater(after);
        });
        show.accept(new Group(card));
        Platform.runLater(search::requestFocus);
    }

    /** What the copies say about where they came from: three choices, starting from Ajustes', and the line as it will go. */
    private static final class Saying {
        final CheckBox who = new CheckBox(I18n.t("forward.sayWho"));
        final CheckBox where = new CheckBox(I18n.t("forward.sayWhere"));
        final CheckBox when = new CheckBox(I18n.t("forward.sayWhen"));
        final Label label = new Label(I18n.t("forward.say"));
        /** On as many lines as they need: at 160 % text they were cut to "Quién lo escr…". */
        final javafx.scene.layout.FlowPane boxes = new javafx.scene.layout.FlowPane(14, 8, who, where, when);
        final Label preview = new Label();

        /** {@code first}: the message whose line is shown, of {@code from}. */
        Saying(Message first, Chat from) {
            ForwardNote saved = ForwardNote.saved();
            who.setSelected(saved.who());
            where.setSelected(saved.where());
            when.setSelected(saved.when());
            label.getStyleClass().add("settings-label");
            for (CheckBox b : List.of(who, where, when)) b.setMinWidth(Region.USE_PREF_SIZE);
            preview.getStyleClass().add("forward-preview");
            preview.setWrapText(true);
            preview.setMinHeight(Region.USE_PREF_SIZE);
            Runnable previewed = () -> {
                String line = note().of(first, from, ZoneId.systemDefault());
                preview.setText(line == null ? I18n.t("forward.saysNothing") : I18n.t("forward.willSay", line));
            };
            for (CheckBox b : List.of(who, where, when)) b.selectedProperty().addListener((o, a, v) -> previewed.run());
            previewed.run();
        }

        ForwardNote note() {
            return new ForwardNote(who.isSelected(), where.isSelected(), when.isSelected());
        }
    }

    private void send(List<Message> messages, Chat from, List<String> to, ForwardNote note) {
        ZoneId zone = ZoneId.systemDefault();
        Forwarding.send(svc, messages, to, m -> note.of(m, from, zone)).thenAccept(failed -> Platform.runLater(() ->
                toast.accept(failed == 0 ? I18n.n("forward.done", to.size()) : I18n.n("forward.failed", failed))));
    }

    /** A chat to choose: its check, picture, name and network. */
    private final class ChatCell extends ListCell<Chat> {
        private final Set<String> chosen;
        private final CheckBox check = new CheckBox();
        private final ChatLine line = new ChatLine(svc);
        private final HBox row;

        ChatCell(Set<String> chosen, Consumer<Chat> toggle) {
            this.chosen = chosen;
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

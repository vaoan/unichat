package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.service.ChatReader;
import app.unichat.service.MediaSource;
import app.unichat.settings.Settings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** ⌘K / Ctrl+K palette: jump to any chat (including archived) or run an app command. */
public final class CommandBar extends VBox {
    public record Command(String label, String hint, Runnable action) {}

    private record Item(String label, String hint, Chat chat, Runnable action) {}

    private final ChatReader chats;
    private final MediaSource media;
    private final TextField field = new TextField();
    private final ObservableList<Item> items = FXCollections.observableArrayList();
    private final ListView<Item> list = new ListView<>(items);
    private final List<Command> commands;
    private final Consumer<Chat> openChat;
    private final Runnable close;

    public CommandBar(ChatReader chats, MediaSource media, List<Command> commands, Consumer<Chat> openChat, Runnable close) {
        this.chats = chats;
        this.media = media;
        this.commands = commands;
        this.openChat = openChat;
        this.close = close;
        getStyleClass().addAll("command-bar", "card");
        setMaxSize(560, 440);
        field.setPromptText("Buscar chats o escribir un comando…");
        field.getStyleClass().add("command-field");
        list.getStyleClass().add("command-list");
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Item it, boolean empty) {
                super.updateItem(it, empty);
                if (empty || it == null) {
                    setGraphic(null);
                    return;
                }
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);
                if (it.chat() != null) {
                    Avatar a = new Avatar(26);
                    a.set(it.chat().title(), it.chat().id(), it.chat().avatarUrl(), media);
                    a.setNetwork(it.chat().network(), Settings.get().showNetworkBadges());
                    row.getChildren().add(a);
                }
                Label l = new Label(it.label());
                l.getStyleClass().add("command-label");
                Region grow = new Region();
                HBox.setHgrow(grow, Priority.ALWAYS);
                Label h = new Label(it.hint());
                h.getStyleClass().add("command-hint");
                row.getChildren().addAll(l, grow, h);
                setGraphic(row);
            }
        });
        list.setOnMouseClicked(e -> run(list.getSelectionModel().getSelectedItem()));
        field.textProperty().addListener((o, a, q) -> filter(q));
        field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case DOWN -> { list.getSelectionModel().selectNext(); e.consume(); }
                case UP -> { list.getSelectionModel().selectPrevious(); e.consume(); }
                case ENTER -> { run(list.getSelectionModel().getSelectedItem()); e.consume(); }
                case ESCAPE -> { close.run(); e.consume(); }
                default -> { }
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        getChildren().addAll(field, list);
        filter("");
    }

    public void focus() {
        field.requestFocus();
    }

    /** Chats offered for {@code q}: never a space (a server/forum container, not a chat you can write in). */
    static List<Chat> chatsFor(List<Chat> all, String q) {
        String nq = ChatFilter.normalize(q);
        return all.stream()
                .filter(c -> !c.space())
                .filter(c -> !c.bridgeBot() || !nq.isEmpty())
                .filter(c -> nq.isEmpty() || ChatFilter.normalize(c.title()).contains(nq))
                .sorted(ChatFilter.ORDER).limit(nq.isEmpty() ? 6 : 30).toList();
    }

    private void filter(String q) {
        String nq = ChatFilter.normalize(q);
        List<Item> out = new ArrayList<>();
        List<Chat> found = chatsFor(chats.chats(), q);
        for (Chat c : found) {
            out.add(new Item(c.title(), c.archived() ? "Archivado · " + c.network().displayName() : c.network().displayName(), c, null));
        }
        for (Command cmd : commands) {
            if (nq.isEmpty() || ChatFilter.normalize(cmd.label()).contains(nq)) out.add(new Item(cmd.label(), cmd.hint(), null, cmd.action()));
        }
        items.setAll(out);
        if (!items.isEmpty()) list.getSelectionModel().select(0);
    }

    private void run(Item it) {
        if (it == null) return;
        close.run();
        if (it.chat() != null) openChat.accept(it.chat());
        else it.action().run();
    }
}

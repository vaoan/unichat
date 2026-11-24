package app.unichat.ui.timeline;

import app.unichat.model.PinnedMessage;
import app.unichat.service.PinnedMessages;
import app.unichat.support.I18n;
import app.unichat.ui.Icons;
import app.unichat.ui.Nodes;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * The open chat's pinned messages, on a bar under its header, as Telegram has it: always in view however far back they
 * are (what they said is kept with the pin), "1 de 3" when there are several. A click goes to the one shown and the bar
 * moves on to the next; its ✕ unpins it.
 */
final class PinnedBar {
    private final HBox box;
    private final Label count = new Label();
    private final Label preview = new Label();
    private final Button go;
    private final Button unpin;
    @SuppressWarnings("UnnecessaryLambda") // one instance: removeListener takes away what addListener added
    private final Consumer<String> onChange = chat -> {
        if (Platform.isFxApplicationThread()) changed(chat);
        else Platform.runLater(() -> changed(chat));
    };
    private String chatId;
    private int index;

    /** {@code reveal}: goes to the message in the chat (loading its history if it must). */
    PinnedBar(Consumer<PinnedMessage> reveal) {
        Label title = new Label(I18n.t("pinned.title"));
        title.getStyleClass().add("pinned-title");
        count.getStyleClass().add("pinned-count");
        preview.getStyleClass().add("pinned-preview");
        preview.setMinWidth(0);
        HBox top = new HBox(6, title, count);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox texts = new VBox(1, top, preview);
        texts.setMinWidth(0);
        go = new Button(null, new HBox(10, Icons.icon(Icons.PIN, 16), texts));
        go.getStyleClass().add("pinned-go");
        go.setMaxWidth(Double.MAX_VALUE);
        go.setAlignment(Pos.CENTER_LEFT);
        go.setMnemonicParsing(false);
        HBox.setHgrow(go, Priority.ALWAYS);
        go.setOnAction(e -> {
            List<PinnedMessage> pins = pins();
            if (pins.isEmpty()) return;
            PinnedMessage shown = pins.get(index % pins.size());
            index = (index + 1) % pins.size(); // the next click goes to the next one, as in Telegram
            redraw();
            reveal.accept(shown);
        });
        unpin = Icons.iconButton(Icons.CLOSE, I18n.t("pinned.unpin"), () -> {
            List<PinnedMessage> pins = pins();
            if (!pins.isEmpty()) PinnedMessages.get().unpin(chatId, pins.get(index % pins.size()).messageId());
        });
        unpin.getStyleClass().add("pinned-unpin");
        box = new HBox(4, go, unpin);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("pinned-bar");
        PinnedMessages.get().addListener(onChange);
        Nodes.show(box, false);
    }

    Node node() { return box; }

    /** The chat opened: its pins, from the newest. */
    void show(String shown) {
        this.chatId = shown;
        index = 0;
        redraw();
    }

    /** The pins stop being heard: the store outlives this chat's view. */
    void dispose() {
        PinnedMessages.get().removeListener(onChange);
    }

    private void changed(String chat) {
        if (chat.equals(chatId)) redraw();
    }

    private List<PinnedMessage> pins() {
        return chatId == null ? List.of() : PinnedMessages.get().of(chatId);
    }

    private void redraw() {
        List<PinnedMessage> pins = pins();
        Nodes.show(box, !pins.isEmpty());
        if (pins.isEmpty()) return;
        index = Math.min(index, pins.size() - 1);
        PinnedMessage p = pins.get(index);
        count.setText(pins.size() > 1 ? I18n.t("pinned.count", index + 1, pins.size()) : "");
        Nodes.show(count, pins.size() > 1);
        String text = p.sender().isEmpty() ? p.preview() : p.sender() + ": " + p.preview();
        preview.setText(text);
        go.setAccessibleText(I18n.t("pinned.goTo", text));
    }
}

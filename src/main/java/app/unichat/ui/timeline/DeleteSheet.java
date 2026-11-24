package app.unichat.ui.timeline;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * Deleting messages for everyone asks twice, in the window's overlay: what goes ("“…” y 2 más") and then "¿Seguro?",
 * with the safe answer focused both times, so neither a stray click nor Enter deletes. Only the second yes does.
 */
public final class DeleteSheet {
    private static final double WIDTH = 380;
    /** How much of the first message the question quotes. */
    private static final int QUOTE = 60;

    private final Consumer<Node> show;
    private final Runnable hide;

    /** {@code show} and {@code hide}: the window's overlay, as the forward sheet uses it. */
    public DeleteSheet(Consumer<Node> show, Runnable hide) {
        this.show = show;
        this.hide = hide;
    }

    /** Asks about deleting {@code messages} (in the chat's order) of {@code chat}; {@code delete} runs after the second yes. */
    void ask(List<Message> messages, Chat chat, Runnable delete) {
        if (messages.isEmpty()) return;
        VBox card = new VBox();
        card.getStyleClass().addAll("card", "folder-dialog", "delete-sheet");
        card.setPrefWidth(WIDTH);
        first(card, messages, chat, delete);
        show.accept(new Group(card)); // in a Group: the overlay stretches a Region to the window's height
    }

    private void first(VBox card, List<Message> messages, Chat chat, Runnable delete) {
        Label preview = new Label(preview(messages));
        preview.getStyleClass().add("delete-preview");
        Button next = danger(I18n.t("delete.next"));
        next.setOnAction(e -> second(card, messages.size(), chat, delete));
        fill(card, I18n.n("delete.title", messages.size()), preview, next);
    }

    private void second(VBox card, int count, Chat chat, Runnable delete) {
        Label why = new Label(I18n.n("delete.sureText", count, chat.title()));
        Button yes = danger(I18n.t("delete.yes"));
        yes.setOnAction(e -> {
            hide.run();
            delete.run();
        });
        fill(card, I18n.t("delete.sure"), why, yes);
    }

    /** The card's question, what it says, and its two answers: the safe one first, and focused. */
    private void fill(VBox card, String question, Label text, Button go) {
        Label title = new Label(question);
        title.getStyleClass().add("sheet-title");
        title.setWrapText(true);
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        Button cancel = new Button(I18n.t("folders.dialog.cancel"));
        cancel.getStyleClass().add("secondary-button");
        cancel.setCancelButton(true);
        cancel.setAccessibleHelp(question + " " + text.getText()); // the focus starts here: a screen reader says what's asked
        cancel.setOnAction(e -> hide.run());
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        card.getChildren().setAll(title, text, new HBox(10, grow, cancel, go));
        Platform.runLater(cancel::requestFocus);
    }

    private static Button danger(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("danger-button");
        return b;
    }

    /** The first message quoted (its text, else its file's name or what it is), and how many more go with it. */
    static String preview(List<Message> messages) {
        Message m = messages.getFirst();
        String body = m.body();
        String name = m.fileName();
        String text = body == null || body.isBlank() ? name == null ? "" : name : body;
        text = text.strip().replaceAll("\\s+", " ");
        if (text.codePointCount(0, text.length()) > QUOTE) text = text.substring(0, text.offsetByCodePoints(0, QUOTE)) + "…";
        String quote = I18n.t("delete.preview", text);
        return messages.size() == 1 ? quote : I18n.t("delete.previewMore", quote, messages.size() - 1);
    }
}

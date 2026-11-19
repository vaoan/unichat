package app.unichat.ui.timeline;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.service.ChatService;
import app.unichat.service.Forwarding;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Reenviar": to which chats, of any network and as many as wanted, and what the copies say about where they came from
 * (who, the chat, when: {@link ForwardNote}), starting from Ajustes' choice, with the line itself shown as it will go.
 * A sheet in the window's overlay, like the rest of the app's questions.
 */
public final class ForwardSheet {
    private static final double WIDTH = 460;

    private final ChatService svc;
    private final Consumer<Node> show;
    private final Runnable hide;
    private final Consumer<Forwarding.Job> progress;

    /** {@code progress}: shows a forward going on ({@link ForwardBar}). */
    public ForwardSheet(ChatService svc, Consumer<Node> show, Runnable hide, Consumer<Forwarding.Job> progress) {
        this.svc = svc;
        this.show = show;
        this.hide = hide;
        this.progress = progress;
    }

    /**
     * Asks where {@code messages} (in the order they go) of {@code from} go; {@code sent} runs when they start going,
     * {@code after} once the sheet closes, however.
     */
    public void open(List<Message> messages, Chat from, Runnable sent, Runnable after) {
        if (messages.isEmpty()) return;
        Label title = new Label(I18n.n("forward.title", messages.size()));
        title.getStyleClass().add("sheet-title");
        Button go = new Button(I18n.t("forward.send"));
        ChatChooser chats = new ChatChooser(svc, to -> go.setDisable(to.isEmpty()));

        Saying saying = new Saying(messages, from);

        Button cancel = new Button(I18n.t("folders.dialog.cancel"));
        cancel.getStyleClass().add("secondary-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> hide.run());
        go.getStyleClass().add("primary-button");
        go.setDefaultButton(true);
        go.setOnAction(e -> {
            ForwardNote note = saying.note();
            boolean gifs = saying.gifs.isSelected();
            List<String> to = chats.chosen();
            hide.run();
            send(messages, from, to, note, gifs);
            sent.run();
        });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, chats.count(), grow, cancel, go);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(12, title, chats.search(), chats.list(), saying.label, saying.boxes, saying.preview, saying.gifs, buttons);
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
        Platform.runLater(chats::focus);
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
        /** Only when a GIF that came as a video goes: as a real GIF where a video wouldn't play by itself (Ajustes' choice to start). */
        final CheckBox gifs = new CheckBox(I18n.t("forward.gifs"));

        /** The first of {@code messages} (of {@code from}) is the one whose line is shown. */
        Saying(List<Message> messages, Chat from) {
            Message first = messages.getFirst();
            gifs.setSelected(app.unichat.video.GifVideos.on());
            gifs.setWrapText(true);
            app.unichat.ui.Nodes.show(gifs, messages.stream().anyMatch(app.unichat.video.GifVideos::is));
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

    private void send(List<Message> messages, Chat from, List<String> to, ForwardNote note, boolean gifs) {
        ZoneId zone = ZoneId.systemDefault();
        progress.accept(Forwarding.send(svc, messages, to, m -> note.of(m, from, zone), gifs));
    }
}

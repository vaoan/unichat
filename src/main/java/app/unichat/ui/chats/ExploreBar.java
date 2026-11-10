package app.unichat.ui.chats;

import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Nodes;
import app.unichat.autosave.AutoSave;
import app.unichat.model.Chat;
import app.unichat.service.ChatReader;
import app.unichat.support.I18n;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * On top of the window while a chat's past is being explored, like the upload bar: which chat (a link to it), what it's
 * doing and how far (the same words as its options, {@link ChatOptions#pastState}), and "Detener". The options can be
 * closed meanwhile. Hidden when nothing is being explored.
 */
public final class ExploreBar extends HBox {
    private final java.util.function.Supplier<List<String>> running;
    private final java.util.function.Function<String, AutoSave.Exploring> state;
    private final Consumer<Runnable> unlisten;
    private final ChatReader chats;
    private final Label what = new Label(I18n.t("exploreBar.title"));
    private final Hyperlink where = new Hyperlink();
    private final Label text = new Label();
    private final ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final Button stop;
    /** Changes come from the saving thread, many per file: one redraw per frame. */
    private final FxCoalescer redraw = new FxCoalescer(this::refresh);
    private final Runnable listener = redraw::request;
    private String chatId;

    public ExploreBar(AutoSave save, ChatReader chats, Consumer<String> openChat) {
        this(save::exploringChats, save::exploring, save::stopExploring, save::addExploringListener, save::removeExploringListener,
                chats, openChat);
    }

    /** What it shows, as functions: the chats being explored, each one's state, stopping one, hearing of changes. */
    ExploreBar(java.util.function.Supplier<List<String>> running, java.util.function.Function<String, AutoSave.Exploring> state,
               Consumer<String> stopOne, Consumer<Runnable> listen, Consumer<Runnable> unlisten, ChatReader chats,
               Consumer<String> openChat) {
        super(10);
        this.running = running;
        this.state = state;
        this.unlisten = unlisten;
        this.chats = chats;
        getStyleClass().addAll("audio-bar", "explore-bar");
        setAlignment(Pos.CENTER_LEFT);
        what.getStyleClass().add("audio-who");
        where.getStyleClass().add("upload-where");
        where.setOnAction(e -> {
            if (chatId != null) openChat.accept(chatId);
        });
        text.getStyleClass().add("audio-time");
        text.setWrapText(true);
        text.setMinWidth(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setMinWidth(80);
        VBox middle = new VBox(3, new HBox(4, what, where), bar, text);
        middle.setMinWidth(0);
        HBox.setHgrow(middle, Priority.ALWAYS);
        stop = new Button(I18n.t("chatOptions.pastStop"));
        stop.getStyleClass().add("secondary-button");
        stop.setOnAction(e -> {
            if (chatId != null) stopOne.accept(chatId);
        });
        getChildren().addAll(middle, stop);
        listen.accept(listener);
        refresh();
    }

    /** The saver outlives this bar (the window is rebuilt on a language change). */
    public void dispose() {
        unlisten.accept(listener);
    }

    void refresh() {
        List<String> now = running.get();
        Nodes.show(this, !now.isEmpty());
        if (now.isEmpty()) {
            chatId = null;
            return;
        }
        chatId = now.contains(chatId) ? chatId : now.getFirst();
        Chat c = chats.chat(chatId);
        where.setText(c == null ? "" : c.title() + (now.size() > 1 ? " " + I18n.t("exploreBar.more", now.size() - 1) : ""));
        AutoSave.Exploring e = state.apply(chatId);
        text.setText(ChatOptions.pastState(e).replace("\n", " · "));
        AutoSave.Now doing = e.now();
        bar.setProgress(doing != null && doing.doing() == AutoSave.Doing.DOWNLOADING && doing.done() >= 0 ? doing.done()
                : ProgressBar.INDETERMINATE_PROGRESS);
        boolean halting = doing != null && doing.doing() == AutoSave.Doing.STOPPING;
        stop.setText(I18n.t(halting ? "chatOptions.pastStopping" : "chatOptions.pastStop"));
        stop.setDisable(halting);
        stop.setAccessibleText(I18n.t("exploreBar.stop", c == null ? "" : c.title()));
    }
}

package app.unichat.ui.timeline;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.service.ChatReader;
import app.unichat.service.Forwarding;
import app.unichat.support.I18n;
import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Icons;
import app.unichat.ui.Nodes;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * On top of the window while messages are forwarded, like the upload bar: to which chats, how far, which message goes
 * now, and a way to stop what's left. Forwards go one at a time (a GIF made from a video takes seconds), and nothing
 * said so. Stays after the end to say how it went: a few seconds when all went, until closed when some didn't.
 */
public final class ForwardBar extends HBox {
    private static final Duration DONE_SHOWN = Duration.seconds(5);

    private final ChatReader chats;
    private final Label what = new Label();
    private final Label now = new Label();
    private final ProgressBar bar = new ProgressBar(0);
    private final Label count = new Label();
    private final Button stop;
    private final Button close;
    private final FxCoalescer redraw = new FxCoalescer(this::refresh);
    private final PauseTransition hideLater = new PauseTransition(DONE_SHOWN);
    private Forwarding.Job job;

    public ForwardBar(ChatReader chats) {
        super(10);
        this.chats = chats;
        getStyleClass().addAll("audio-bar", "upload-bar", "forward-bar");
        setAlignment(Pos.CENTER_LEFT);
        what.getStyleClass().add("audio-who");
        what.setMinWidth(0);
        now.getStyleClass().add("audio-time");
        now.setMinWidth(0);
        VBox texts = new VBox(0, what, now);
        texts.setAlignment(Pos.CENTER_LEFT);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        bar.setPrefWidth(160);
        bar.setMinWidth(80);
        count.getStyleClass().add("upload-percent");
        count.setMinWidth(Region.USE_PREF_SIZE);
        stop = Icons.iconButton(Icons.CLOSE, I18n.t("forwardBar.stop"), () -> {
            if (job != null) job.cancel();
        });
        close = Icons.iconButton(Icons.CLOSE, I18n.t("forwardBar.close"), this::dismiss);
        hideLater.setOnFinished(e -> dismiss());
        getChildren().addAll(texts, bar, count, stop, close);
        Nodes.show(this, false);
    }

    /** Follows {@code forwarding} from now on, in place of the one before (which goes on, unseen). */
    public void show(Forwarding.Job forwarding) {
        hideLater.stop();
        job = forwarding;
        forwarding.addListener(() -> {
            if (forwarding == job) redraw.request();
        });
        refresh();
    }

    private void dismiss() {
        hideLater.stop();
        job = null;
        refresh();
    }

    private void refresh() {
        Forwarding.Job j = job;
        Nodes.show(this, j != null);
        if (j == null) return;
        int went = j.sent() + j.failed();
        bar.setProgress(j.total() == 0 ? 1 : went / (double) j.total());
        count.setText(I18n.t("forwardBar.count", went, j.total()));
        Nodes.show(stop, !j.finished());
        Nodes.show(close, j.finished());
        if (!j.finished()) {
            what.setText(I18n.t(j.cancelled() ? "forwardBar.stopping" : "forwardBar.going", where(j)));
            now.setText(j.current() == null ? "" : shown(j.current()));
            return;
        }
        what.setText(j.failed() > 0 ? I18n.n("forwardBar.failed", j.failed(), j.sent())
                : I18n.t(j.cancelled() ? "forwardBar.stopped" : "forwardBar.done", j.sent(), where(j)));
        now.setText("");
        if (j.failed() == 0) hideLater.playFromStart();
        else hideLater.stop();
    }

    /** "Buga", or "Buga y 2 más". */
    private String where(Forwarding.Job j) {
        Chat first = chats.chat(j.chatIds().getFirst());
        String name = first == null ? "" : first.title();
        int more = j.chatIds().size() - 1;
        return more == 0 ? name : I18n.n("forwardBar.andMore", more, name);
    }

    /** The message going, in a few words: its file's name, or the start of its text. */
    private static String shown(Message m) {
        String s = m.isMedia() && m.fileName() != null ? m.fileName() : m.body().strip().replaceAll("\\s+", " ");
        return s.length() > 60 ? s.substring(0, 59) + "…" : s;
    }
}

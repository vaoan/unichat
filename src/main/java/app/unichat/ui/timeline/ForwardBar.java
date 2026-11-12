package app.unichat.ui.timeline;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.service.ChatReader;
import app.unichat.service.Forwarding;
import app.unichat.support.I18n;
import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Icons;
import app.unichat.ui.Nodes;
import app.unichat.ui.TaskBar;
import javafx.animation.PauseTransition;
import javafx.scene.control.Button;
import javafx.util.Duration;

/**
 * On top of the window while messages are forwarded, like the upload bar: to which chats, how far, which message goes
 * now, and a way to stop what's left. Forwards go one at a time (a GIF made from a video takes seconds), and nothing
 * said so. Stays after the end to say how it went: a few seconds when all went, until closed when some didn't.
 */
public final class ForwardBar extends TaskBar {
    private static final Duration DONE_SHOWN = Duration.seconds(5);

    private final ChatReader chats;
    private final Button stop;
    private final Button close;
    private final FxCoalescer redraw = new FxCoalescer(this::refresh);
    private final PauseTransition hideLater = new PauseTransition(DONE_SHOWN);
    private Forwarding.Job job;
    private String chatId;

    public ForwardBar(ChatReader chats, java.util.function.Consumer<String> openChat) {
        super("forward-bar");
        this.chats = chats;
        lead.getChildren().setAll(Icons.icon(Icons.FORWARD, 20));
        where.setOnAction(e -> {
            if (chatId != null) openChat.accept(chatId);
        });
        stop = Icons.iconButton(Icons.CLOSE, I18n.t("forwardBar.stop"), () -> {
            if (job != null) job.cancel();
        });
        close = Icons.iconButton(Icons.CLOSE, I18n.t("forwardBar.close"), this::dismiss);
        hideLater.setOnFinished(e -> dismiss());
        actions(stop, close);
        Nodes.show(this, false);
    }

    /** Follows {@code forwarding} from now on, in place of the one before (which goes on, unseen). */
    public void show(Forwarding.Job forwarding) {
        hideLater.stop();
        job = forwarding;
        lead.getChildren().setAll(Icons.icon(Icons.FORWARD, 20));
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
        big.setText(I18n.t("forwardBar.count", went, j.total()));
        Nodes.show(stop, !j.finished());
        Nodes.show(close, j.finished());
        chatId = j.currentChat() != null ? j.currentChat() : j.chatIds().getFirst();
        Chat c = chats.chat(chatId);
        where.setText(I18n.t("uploadBar.where", c == null ? "" : c.title()));
        if (!j.finished()) {
            what.setText(I18n.t(j.cancelled() ? "forwardBar.stopping" : "forwardBar.going", where(j)));
            detail.setText(j.current() == null ? "" : shown(j.current()));
            stage.setText("");
            hideLater.stop();
        } else {
            what.setText(j.failed() > 0 ? I18n.n("forwardBar.failed", j.failed(), j.sent())
                    : I18n.t(j.cancelled() ? "forwardBar.stopped" : "forwardBar.done", j.sent(), where(j)));
            detail.setText("");
            where.setText("");
            lead.getChildren().setAll(Icons.icon(j.failed() > 0 ? Icons.ERROR : Icons.CHECK, 22));
            if (j.failed() == 0) hideLater.playFromStart();
            else hideLater.stop();
        }
        tidy();
        bar.setAccessibleText(what.getText() + ". " + big.getText());
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

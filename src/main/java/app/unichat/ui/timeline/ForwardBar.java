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
import org.jspecify.annotations.Nullable;

/**
 * On top of the window while messages are forwarded, like the upload bar: to which chats, how far, which message goes
 * now, and a way to stop what's left. Forwards go one at a time (a GIF made from a video takes seconds), and nothing
 * said so. Stays after the end to say how it went: a few seconds when all went, until closed when some didn't. The job
 * shown is kept per service, so a bar built again (the window let go while closed) follows it on, or still says what
 * failed.
 */
public final class ForwardBar extends TaskBar {
    private static final Duration DONE_SHOWN = Duration.seconds(5);
    /** The forward each service's bar follows, until it's closed: outlives the bar (the window's view let go). */
    private static final java.util.Map<ChatReader, Forwarding.Job> FOLLOWED = new java.util.WeakHashMap<>();

    private final ChatReader chats;
    private final Button stop;
    private final Button close;
    private final FxCoalescer redraw = new FxCoalescer(this::refresh);
    private final PauseTransition hideLater = new PauseTransition(DONE_SHOWN);
    private Forwarding.@Nullable Job job;
    private @Nullable String chatId;
    /** Told by the job on each change; removed when another is followed or the bar is let go. */
    @SuppressWarnings("UnnecessaryLambda") // one instance: removeListener takes away what addListener added
    private final Runnable onChange = redraw::request;

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
        Forwarding.Job followed = followed(chats);
        // One still going, or whose failures weren't read yet; one that all went said so already.
        if (followed != null && (!followed.finished() || followed.failed() > 0)) show(followed);
        else forget(chats, followed);
    }

    /** Follows {@code forwarding} from now on, in place of the one before (which goes on, unseen). */
    public void show(Forwarding.Job forwarding) {
        hideLater.stop();
        Forwarding.Job before = job;
        if (before != null) before.removeListener(onChange);
        job = forwarding;
        synchronized (FOLLOWED) {
            FOLLOWED.put(chats, forwarding);
        }
        lead.getChildren().setAll(Icons.icon(Icons.FORWARD, 20));
        forwarding.addListener(onChange);
        refresh();
    }

    /** The bar let go with its window: the job goes on (a bar built again follows it), no longer telling this one. */
    public void dispose() {
        hideLater.stop();
        Forwarding.Job j = job;
        if (j != null) j.removeListener(onChange);
    }

    private void dismiss() {
        hideLater.stop();
        Forwarding.Job j = job;
        job = null;
        if (j != null) {
            j.removeListener(onChange);
            forget(chats, j);
        }
        refresh();
    }

    private static Forwarding.@Nullable Job followed(ChatReader chats) {
        synchronized (FOLLOWED) {
            return FOLLOWED.get(chats);
        }
    }

    private static void forget(ChatReader chats, Forwarding.@Nullable Job j) {
        if (j == null) return;
        synchronized (FOLLOWED) {
            FOLLOWED.remove(chats, j);
        }
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
            Message going = j.current();
            detail.setText(going == null ? "" : shown(going));
            stage.setText("");
            hideLater.stop();
        } else {
            what.setText(j.failed() > 0 ? I18n.n("forwardBar.failed", j.failed(), j.sent())
                    : I18n.t(j.cancelled() ? "forwardBar.stopped" : "forwardBar.done", j.sent(), where(j)));
            String why = j.refused();
            detail.setText(j.failed() > 0 && why != null ? why : ""); // a GIF no MP4 could be made of says so
            where.setText("");
            lead.getChildren().setAll(Icons.icon(j.failed() > 0 ? Icons.ERROR : Icons.CHECK, 22));
            if (j.failed() == 0) hideLater.playFromStart();
            else hideLater.stop();
        }
        tidy();
        bar.setAccessibleText(what.getText() + ". " + big.getText() + (detail.getText().isEmpty() ? "" : ". " + detail.getText()));
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
        String name = m.fileName();
        String s = m.isMedia() && name != null ? name : m.body().strip().replaceAll("\\s+", " ");
        return s.length() > 60 ? s.substring(0, 59) + "…" : s;
    }
}

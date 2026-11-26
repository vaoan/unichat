package app.unichat.ui.chats;

import app.unichat.model.Network;
import app.unichat.service.OlderChats;
import app.unichat.service.OlderChats.Answer;
import app.unichat.service.OlderChats.Loading;
import app.unichat.service.OlderChats.Stage;
import app.unichat.support.I18n;
import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Icons;
import app.unichat.ui.Nodes;
import app.unichat.ui.TaskBar;
import javafx.animation.PauseTransition;
import javafx.scene.AccessibleRole;
import javafx.scene.control.ProgressBar;
import javafx.util.Duration;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * On top of the window while "Load older chats" runs: the network, its stage (restarting its bridge, asking, chats
 * arriving: N new), N of M networks; then each network's answer for a few seconds, and it hides. Reachable with Tab,
 * and a screen reader says it all.
 */
public final class OlderChatsBar extends TaskBar {
    /** How long the answers stay. */
    private static final Duration RESULT = Duration.seconds(6);

    private final Supplier<@Nullable Loading> state;
    private final Consumer<Runnable> unlisten;
    private final Consumer<Runnable> later;
    /** Changes come from the service's thread: one redraw per frame. */
    private final FxCoalescer redraw = new FxCoalescer(this::refresh);
    private final Runnable listener = redraw::request;
    /**
     * The last end said, or the one the service had when the bar was built (a run before a language change rebuilt
     * the window): not said again. Any other end is, even unseen running: the loader can go straight to DONE (an
     * account gone before Load) or tell a stage and DONE within one frame, and the coalesced redraw sees only DONE.
     */
    private @Nullable Loading said;
    /** The end being said, until a moment has passed. */
    private @Nullable Loading saying;
    private boolean disposed;

    public OlderChatsBar(OlderChats svc) {
        this(svc::loading, svc::addLoadingListener, svc::removeLoadingListener, OlderChatsBar::afterAMoment);
    }

    /**
     * For the window: "Load older chats" at the end of {@code list} opens its warning ({@link OlderChatsSheet} in the
     * overlay, {@code show} and {@code hide}) and Load runs it; the bar follows the run. A service without older chats
     * gets a bar that never shows, and no button.
     */
    public static OlderChatsBar of(app.unichat.service.ChatService svc, ChatListPane list, Consumer<javafx.scene.Node> show, Runnable hide) {
        if (!(svc instanceof OlderChats older)) return new OlderChatsBar(() -> null, l -> { }, l -> { }, OlderChatsBar::afterAMoment);
        OlderChatsSheet sheet = new OlderChatsSheet(show, hide);
        list.setOnLoadOlderChats(nets -> sheet.open(older.plans(nets), run -> { var _ = older.load(run); }));
        return new OlderChatsBar(older);
    }

    /** What it shows, as functions; {@code later} runs a job once the answers have been seen long enough. */
    OlderChatsBar(Supplier<@Nullable Loading> state, Consumer<Runnable> listen, Consumer<Runnable> unlisten, Consumer<Runnable> later) {
        super("older-chats-bar");
        this.state = state;
        this.unlisten = unlisten;
        this.later = later;
        lead.getChildren().setAll(Icons.icon(Icons.CHAT, 20));
        what.setWrapText(true);
        stage.setWrapText(true);
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.TEXT);
        listen.accept(listener);
        Loading before = state.get();
        said = running(before) ? null : before;
        refresh();
    }

    /** The service outlives this bar (the window is rebuilt on a language change). */
    public void dispose() {
        disposed = true;
        unlisten.accept(listener);
    }

    private static void afterAMoment(Runnable job) {
        PauseTransition p = new PauseTransition(RESULT);
        p.setOnFinished(e -> job.run());
        p.play();
    }

    private static boolean running(@Nullable Loading l) {
        return l != null && l.stage() != Stage.DONE;
    }

    void refresh() {
        Loading l = state.get();
        boolean running = running(l);
        if (l != null && !running && l != said) {
            said = l;
            saying = l;
            later.accept(() -> {
                if (saying != l || disposed) return;
                saying = null;
                refresh();
            });
        }
        boolean done = l != null && l == saying;
        Nodes.show(this, running || done);
        if (l != null && running) showRunning(l);
        else if (l != null && done) showDone(l);
    }

    private void showRunning(Loading l) {
        what.setText(I18n.t("olderChats.bar.loading", l.network().displayName()));
        stage.setText(switch (l.stage()) {
            case RESTARTING -> I18n.t("olderChats.bar.restarting");
            case ASKING -> I18n.t("olderChats.bar.asking");
            case ARRIVING, DONE -> I18n.n("olderChats.bar.arriving", l.newChats());
        });
        big.setText(I18n.t("olderChats.bar.count", Math.min(l.done() + 1, l.total()), l.total()));
        small.setText(I18n.t("olderChats.bar.networks"));
        bar.setProgress(l.total() == 0 ? ProgressBar.INDETERMINATE_PROGRESS : (double) l.done() / l.total());
        said();
    }

    private void showDone(Loading l) {
        what.setText(result(l.answers()));
        stage.setText("");
        big.setText("");
        small.setText("");
        bar.setProgress(1);
        said();
    }

    private void said() {
        setAccessibleText(String.join(" ", what.getText(), stage.getText(), big.getText(), small.getText()).strip());
        tidy();
    }

    /** "37 new chats: Telegram 30, Discord 7. WhatsApp: nothing more. Instagram: not possible, …" */
    static String result(Map<Network, Answer> answers) {
        List<String> loaded = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        int total = 0;
        for (Map.Entry<Network, Answer> e : answers.entrySet()) {
            String name = e.getKey().displayName();
            switch (e.getValue()) {
                case Answer.Loaded(int chats) -> {
                    total += chats;
                    loaded.add(I18n.t("olderChats.bar.loadedFrom", name, chats));
                }
                case Answer.NothingMore() -> rest.add(I18n.t("olderChats.bar.nothingMore", name));
                case Answer.NotPossible(String reason) -> rest.add(I18n.t("olderChats.bar.notPossible", name, reason));
            }
        }
        List<String> out = new ArrayList<>();
        out.add(loaded.isEmpty() ? I18n.t("olderChats.bar.noneNew") : I18n.n("olderChats.bar.loaded", total, String.join(", ", loaded)));
        out.addAll(rest);
        return String.join(" ", out);
    }
}

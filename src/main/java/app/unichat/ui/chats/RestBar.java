package app.unichat.ui.chats;

import app.unichat.service.ArchiveWaking;
import app.unichat.service.ArchiveWaking.Step;
import app.unichat.service.ArchiveWaking.Waking;
import app.unichat.support.I18n;
import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Icons;
import app.unichat.ui.Nodes;
import app.unichat.ui.TaskBar;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.AccessibleRole;
import javafx.util.Duration;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tells the service when the archive is open and what's opened in it, and on top of the window while what rested
 * wakes (archived Discord servers and channels, {@link ArchiveWaking}): chats catching up, N of M; a server opened in
 * the archive receiving everything again; a catch-up that couldn't finish; then "Al día" for a few seconds. Hidden
 * otherwise. Reachable with Tab, and a screen reader says it all.
 */
public final class RestBar extends TaskBar {
    /** How long "Al día" stays. */
    private static final Duration UP_TO_DATE = Duration.seconds(4);

    private final Supplier<Waking> state;
    private final Consumer<Runnable> unlisten;
    private final Consumer<Runnable> later;
    private final ObservableValue<Boolean> archiveOpen;
    private final Consumer<String> tellOpened;
    private final Consumer<String> tellShown;
    private final ChangeListener<Boolean> onOpen;
    /** Changes come from the service's thread: one redraw per frame. */
    private final FxCoalescer redraw = new FxCoalescer(this::refresh);
    private final Runnable listener = redraw::request;
    /** This bar saw something wake, so its end is worth saying. */
    private boolean sawWaking;
    /** The end being said ("Al día"), until a moment has passed. */
    private Waking saying;
    private boolean disposed;

    /** {@code archiveOpen}: the archive showing in the window; kept here, so a binding passed in lives as long as the bar. */
    public RestBar(ArchiveWaking svc, ObservableValue<Boolean> archiveOpen) {
        this(svc::waking, svc::addWakingListener, svc::removeWakingListener, svc::setArchiveOpen, svc::archivedChatOpened,
                svc::setOpenChat, archiveOpen, RestBar::afterAMoment);
    }

    /**
     * For the window: the archive is open while Archivados shows in the list, and a server opened there is told. A hidden window doesn't leave it (unseen, the
     * app is quiet already, and every show woke it all again).
     */
    public static RestBar of(ArchiveWaking svc, ChatListPane list) {
        ObservableValue<ChatFilter> filter = list.filterProperty();
        RestBar bar = new RestBar(svc, javafx.beans.binding.Bindings.createBooleanBinding(
                () -> filter.getValue().view() == ChatFilter.View.ARCHIVE, filter));
        list.setOnSpaceOpened(bar::serverOpened);
        return bar;
    }

    /** What it shows and tells, as functions; {@code later} runs a job once "Al día" has been seen long enough. */
    RestBar(Supplier<Waking> state, Consumer<Runnable> listen, Consumer<Runnable> unlisten, Consumer<Boolean> tellOpen,
            Consumer<String> tellOpened, Consumer<String> tellShown, ObservableValue<Boolean> archiveOpen, Consumer<Runnable> later) {
        super("rest-bar");
        this.state = state;
        this.unlisten = unlisten;
        this.later = later;
        this.archiveOpen = archiveOpen;
        this.tellOpened = tellOpened;
        this.tellShown = tellShown;
        lead.getChildren().setAll(Icons.icon(Icons.ARCHIVE, 20));
        stage.setWrapText(true);
        what.setWrapText(true);
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.TEXT);
        onOpen = (o, was, open) -> tellOpen.accept(open);
        archiveOpen.addListener(onOpen);
        tellOpen.accept(archiveOpen.getValue());
        listen.accept(listener);
        Waking now = state.get();
        sawWaking = now != null && now.step() != Step.DONE;
        refresh();
    }

    /** A chat shown in the window: it never rests; in the archive, an archived one brings its server back. */
    public void chatOpened(app.unichat.model.Chat c) {
        if (c == null) return;
        tellShown.accept(c.id());
        serverOpened(c);
    }

    /** A server or group whose channels are shown: in the archive, an archived one comes back at the bridge. */
    void serverOpened(app.unichat.model.Chat c) {
        if (c != null && c.archived() && Boolean.TRUE.equals(archiveOpen.getValue())) tellOpened.accept(c.id());
    }

    /** The service outlives this bar (the window is rebuilt on a language change). */
    public void dispose() {
        disposed = true;
        unlisten.accept(listener);
        archiveOpen.removeListener(onOpen);
    }

    private static void afterAMoment(Runnable job) {
        PauseTransition p = new PauseTransition(UP_TO_DATE);
        p.setOnFinished(e -> job.run());
        p.play();
    }

    void refresh() {
        Waking w = state.get();
        boolean waking = w != null && w.step() != Step.DONE;
        if (waking) sawWaking = true;
        if (w != null && w.step() == Step.DONE && sawWaking) {
            sawWaking = false;
            saying = w;
            later.accept(() -> {
                if (saying != w || disposed) return;
                saying = null;
                refresh();
            });
        }
        boolean upToDate = w != null && w == saying;
        Nodes.show(this, waking || upToDate);
        if (!waking && !upToDate) return;
        boolean server = !upToDate && w.step() == Step.SERVER;
        boolean counts = !upToDate && !server;
        what.setText(upToDate ? I18n.t("restBar.done") : server ? I18n.t("restBar.server", w.server())
                : I18n.t(w.step() == Step.RETRYING ? "restBar.retrying" : "restBar.waking"));
        stage.setText(counts ? I18n.t("restBar.chats") : "");
        big.setText(counts ? I18n.t("restBar.count", w.done(), w.total()) : "");
        bar.setProgress(upToDate ? 1 : server ? javafx.scene.control.ProgressBar.INDETERMINATE_PROGRESS : w.fraction());
        setAccessibleText(String.join(" ", what.getText(), stage.getText(), big.getText()).strip());
        tidy();
    }
}

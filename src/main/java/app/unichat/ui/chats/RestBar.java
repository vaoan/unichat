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
 * Tells the service when the archive is open, and on top of the window while what rested wakes (archived Discord
 * servers and channels, {@link ArchiveWaking}): that it's coming back and receiving everything again, which step, N of
 * M, then "Al día" for a few seconds. Hidden otherwise. Reachable with Tab, and a screen reader says it all.
 */
public final class RestBar extends TaskBar {
    /** How long "Al día" stays. */
    private static final Duration UP_TO_DATE = Duration.seconds(4);

    private final Supplier<Waking> state;
    private final Consumer<Runnable> unlisten;
    private final Consumer<Runnable> later;
    private final ObservableValue<Boolean> archiveOpen;
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
        this(svc::waking, svc::addWakingListener, svc::removeWakingListener, svc::setArchiveOpen, archiveOpen, RestBar::afterAMoment);
    }

    /** For the window: the archive is open while Archivados shows in it and the window is there to see it. */
    public static RestBar of(ArchiveWaking svc, javafx.stage.Window window, ObservableValue<ChatFilter> filter) {
        return new RestBar(svc, javafx.beans.binding.Bindings.createBooleanBinding(
                () -> window.isShowing() && filter.getValue().view() == ChatFilter.View.ARCHIVE, window.showingProperty(), filter));
    }

    /** What it shows and tells, as functions; {@code later} runs a job once "Al día" has been seen long enough. */
    RestBar(Supplier<Waking> state, Consumer<Runnable> listen, Consumer<Runnable> unlisten, Consumer<Boolean> tellOpen,
            ObservableValue<Boolean> archiveOpen, Consumer<Runnable> later) {
        super("rest-bar");
        this.state = state;
        this.unlisten = unlisten;
        this.later = later;
        this.archiveOpen = archiveOpen;
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
        what.setText(I18n.t(upToDate ? "restBar.done" : "restBar.waking"));
        stage.setText(upToDate ? "" : I18n.t(w.step() == Step.SERVERS ? "restBar.servers" : "restBar.chats"));
        big.setText(upToDate ? "" : I18n.t("restBar.count", w.done(), w.total()));
        bar.setProgress(upToDate ? 1 : w.fraction());
        setAccessibleText(String.join(" ", what.getText(), stage.getText(), big.getText()).strip());
        tidy();
    }
}

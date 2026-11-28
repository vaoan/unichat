package app.unichat.ui;

import app.unichat.support.I18n;
import app.unichat.ui.media.Pictures;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The main window's view, let go while the window is closed to the menu bar: after {@link #AWAY_RELEASE} hidden (not
 * minimised), with no other Unichat window showing and nothing playing or recording, the view is disposed and every
 * picture it decoded forgotten, so the memory goes back. The session goes on without it ({@link SessionAlerts}: the
 * notifications and the menu bar's count). Opening the window shows a loading screen at once, then builds the view
 * again with the chat that was open. A language change builds it again the same way ({@link #rebuild}).
 */
public final class AwayRelease {
    /** How long the window stays closed before its view is let go: a quick hide and show keeps it as it was. */
    public static final java.time.Duration AWAY_RELEASE = java.time.Duration.ofSeconds(60);

    private final Stage stage;
    private final Scene scene;
    private final PauseTransition away;
    /** Builds the session's view; null on the login screen. */
    private @Nullable Supplier<MainView> build;
    private @Nullable MainView view;
    /** The chat that was open when the view was let go, opened again in the next one. */
    private @Nullable String chatLetGo;
    /** What the cloud said last: a view built again shows it on its gear at once, not at the next change. */
    private app.unichat.cloud.Cloud.@Nullable Status cloud;
    /** What waits for the view being built again (a notification's chat). */
    private final List<Consumer<MainView>> waiting = new ArrayList<>();
    /** Changes with each session and each build asked for, so a build that comes late can tell it isn't wanted. */
    private int round;
    private boolean building;
    private int released;
    private int held;
    /** The collection of the last cleanup; tests replace it. */
    volatile Runnable collector = System::gc;
    /** The steps of the last cleanups, in order (for tests): gc, image, gc, root. */
    final List<String> cleanupSteps = new java.util.concurrent.CopyOnWriteArrayList<>();

    public AwayRelease(Stage stage, Scene scene) {
        this(stage, scene, AWAY_RELEASE);
    }

    /** {@code after}: how long hidden before letting go (tests give zero). */
    AwayRelease(Stage stage, Scene scene, java.time.Duration after) {
        this.stage = stage;
        this.scene = scene;
        away = new PauseTransition(Duration.millis((double) Math.max(1, after.toMillis()))); // at least a pulse: never at once inside hide()
        away.setOnFinished(e -> letGoIfAway());
        stage.showingProperty().addListener((o, was, showing) -> {
            if (showing) shown();
            else if (view != null) away.playFromStart();
        });
    }

    /** The session's window, built now and again on each opening after it was let go. FX thread. */
    public MainView start(Supplier<MainView> builder) {
        clear();
        build = builder;
        return put(builder.get(), null);
    }

    /**
     * Built again now (the language changed), with the same chat open; null when there's no view (let go, or the login
     * screen): the next opening builds it, in the new language.
     */
    public @Nullable MainView rebuild() {
        MainView old = view;
        Supplier<MainView> b = build;
        if (old == null || b == null) return null;
        String chat = old.currentChatId();
        old.dispose();
        return put(b.get(), chat);
    }

    /** The session ended (the login screen): its view disposed, nothing built again. The caller sets the next root. */
    public void clear() {
        away.stop();
        round++;
        building = false;
        build = null;
        chatLetGo = null;
        cloud = null;
        waiting.clear();
        MainView old = view;
        view = null;
        if (old != null) old.dispose();
    }

    /** The view, or null while let go or on the login screen. */
    public @Nullable MainView view() {
        return view;
    }

    /** The chat open in the view; null when none, or no view. */
    public @Nullable String currentChatId() {
        MainView v = view;
        return v == null ? null : v.currentChatId();
    }

    /** {@code then} on the view: now, or once it's built again (a notification clicked while let go). Nothing without a session. */
    public void whenBuilt(Consumer<MainView> then) {
        MainView v = view;
        if (v != null) then.accept(v);
        else if (build != null) waiting.add(then);
    }

    /**
     * A notification's click, once the window is asked to show: its chat (and message) in the view, built again first
     * when it was let go (the loading screen meanwhile); the log says what it led to.
     */
    public void openNotified(String target) {
        String id = app.unichat.ui.desktop.NotificationTarget.chat(target);
        String message = app.unichat.ui.desktop.NotificationTarget.message(target);
        Platform.runLater(() -> whenBuilt(v -> { // queued after the window's showing, which starts the build
            v.openChatId(id);
            if (message != null) v.showMessage(id, message); // a notice about one message (a file not saved)
            Platform.runLater(() -> System.err.println("Notification click: " + v.openOutcome(id)));
        }));
    }

    /**
     * Settings adopted from another computer: what the whole app reads once (the language, the shortcut, the Dock, the
     * tray) at once, view or not; the view's redraw only when there's one (one built later reads them anyway).
     */
    public void settingsSynced() {
        MainView v = view;
        if (v != null) v.settingsSynced();
        else app.unichat.ui.settings.SyncedSettings.apply(() -> { });
    }

    /** What the cloud is doing, for the view's gear: now if there's one, and on each built again. UI thread. */
    public void cloudChanged(app.unichat.cloud.Cloud.Status s) {
        cloud = s;
        MainView v = view;
        if (v != null) v.cloudChanged(s);
    }

    /** Views let go so far (for tests: they wait for it). */
    int released() {
        return released;
    }

    /** Times it was due but something kept it (another window, audio playing). */
    int held() {
        return held;
    }

    private MainView put(MainView v, @Nullable String chat) {
        view = v;
        scene.setRoot(v);
        Theme.applyAll();
        app.unichat.cloud.Cloud.Status c = cloud;
        if (c != null) v.cloudChanged(c);
        if (chat != null) v.openChatId(chat);
        List<Consumer<MainView>> then = List.copyOf(waiting);
        waiting.clear();
        then.forEach(t -> t.accept(v));
        return v;
    }

    private void shown() {
        away.stop();
        if (view != null || build == null || building) return;
        // Never a frozen or blank window: what it's doing shows first, and the view is built once that has been drawn.
        building = true;
        int mine = round;
        scene.setRoot(new LoadingView(I18n.t("main.opening")));
        Theme.applyAll();
        Redraws.PENDING.incrementAndGet(); // tests wait for the view as for any redraw
        Runnable[] drawn = new Runnable[1];
        drawn[0] = () -> {
            scene.removePostLayoutPulseListener(drawn[0]);
            Platform.runLater(() -> {
                try {
                    builtAgain(mine);
                } finally {
                    Redraws.PENDING.decrementAndGet();
                }
            });
        };
        scene.addPostLayoutPulseListener(drawn[0]);
        Platform.requestNextPulse();
    }

    private void builtAgain(int mine) {
        Supplier<MainView> b = build;
        if (mine != round || b == null) return; // logged out meanwhile: the login screen is there
        building = false;
        if (!stage.isShowing()) return; // closed again before it was built: built on the next opening
        String chat = chatLetGo;
        MainView built;
        try {
            built = b.get();
        } catch (RuntimeException e) { // never a wheel spinning for good: it says so, and the next opening tries again
            System.err.println("The main window could not be built again: " + e);
            if (scene.getRoot() instanceof LoadingView loading) loading.fail(I18n.t("main.openFailed", app.unichat.support.Errors.message(e)));
            return;
        }
        chatLetGo = null;
        put(built, chat);
    }

    private void letGoIfAway() {
        MainView v = view;
        if (v == null) return; // nothing to let go (showing the window again stops this timer)
        if (othersShowing() || v.busy()) {
            held++;
            away.playFromStart(); // asked again later: what keeps it ends by itself
            return;
        }
        letGo(v);
    }

    /**
     * The settings, a photo or a video open: what's in them comes from the view, which stays. A menu, a tooltip or the
     * notification card isn't one ({@link app.unichat.ui.desktop.AppWindows#open}).
     */
    private boolean othersShowing() {
        for (Window w : Window.getWindows()) if (w != stage && app.unichat.ui.desktop.AppWindows.open(w)) return true;
        return false;
    }

    private void letGo(MainView v) {
        chatLetGo = v.currentChatId();
        v.dispose(); // what it registered on the stage and the app's singletons, or they would keep it
        view = null;
        StackPane empty = new StackPane();
        scene.setRoot(empty);
        // The focus leaves it too: a message box keeping it blinks its caret for good, and JavaFX's timer holds the view.
        empty.requestFocus();
        // A hidden window draws nothing, so its scene keeps the nodes changed since its last frame (Scene.dirtyNodes), the
        // view among them, until it's drawn again. A snapshot of the empty root syncs that list now and lets them go.
        empty.snapshot(null, new javafx.scene.image.WritableImage(1, 1));
        Pictures.forget();
        released++;
        cleanUp(empty);
    }

    /**
     * What JavaFX keeps on the graphics card is freed only by {@code Disposer.cleanUp()} on the render thread, which runs at
     * the end of a painted frame or a snapshot (PresentingPainter:117, UploadingPainter:196, QuantumToolkit:1723): a GC only
     * queues the textures, and a hidden window paints none. The image textures sit in a WeakHashMap that is purged only when
     * an ImageView is drawn (BaseResourceFactory:61-63, NGImageView:121). So: GC, a snapshot of a tiny ImageView (the stale
     * entries drop), GC again (their textures are queued), then a snapshot of the empty root (cleanUp runs). Each GC runs off
     * the FX thread and the next step comes back to it when it returns; nothing waits. Stops if the window is shown again.
     */
    private void cleanUp(StackPane empty) {
        collectThen(empty, () -> {
            javafx.scene.image.WritableImage pixel = new javafx.scene.image.WritableImage(1, 1);
            new javafx.scene.image.ImageView(pixel).snapshot(null, new javafx.scene.image.WritableImage(1, 1));
            cleanupSteps.add("image");
            collectThen(empty, () -> {
                empty.snapshot(null, new javafx.scene.image.WritableImage(1, 1));
                cleanupSteps.add("root");
            });
        });
    }

    /** A GC on a virtual thread (G1 gives the memory back at once; its pause stops the FX thread too, harmless hidden), then {@code next} on the FX thread, if the window is still away. */
    private void collectThen(StackPane empty, Runnable next) {
        Runnable gc = collector;
        Thread.ofVirtual().name("away-gc").start(() -> {
            gc.run();
            cleanupSteps.add("gc");
            Platform.runLater(() -> {
                if (!stage.isShowing() && scene.getRoot() == empty) next.run();
            });
        });
    }
}

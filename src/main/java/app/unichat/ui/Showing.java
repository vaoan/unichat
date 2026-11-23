package app.unichat.ui;

import app.unichat.support.Seen;
import javafx.animation.Animation;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Work a node does only while it can be seen: in a scene whose window shows, not minimised, while anyone can see
 * Unichat ({@link Seen}). Animations used to stop when their node left its scene, but a closed window keeps its scene,
 * so a GIF or sticker in a photo viewer, the typing dots of a closed window, went on running for good: held by JavaFX's
 * timer with all their window, and holding their share of the animation budget.
 */
public final class Showing {
    /** The watches whose window shows now, the only ones {@link Seen} can change; FX thread only. */
    private static final Set<Watcher> SHOWN_WINDOWS = Collections.newSetFromMap(new IdentityHashMap<>());

    static {
        // One listener for all, holding no node: a watch is only in SHOWN_WINDOWS while its window shows. Always handed
        // to the FX thread, even from there: Seen calls its listeners under its lock, and the work here can be anything.
        Seen.get().addListener(seen -> Platform.runLater(Showing::seenChanged));
    }

    private Showing() {}

    /** What {@link #whileShowing} returns: stops watching (a node handed another animation). */
    public interface Watch {
        void cancel();
    }

    /** Runs {@code start} each time {@code node} comes to be seen and {@code stop} each time it stops being. */
    public static Watch whileShowing(Node node, Runnable start, Runnable stop) {
        return new Watcher(node, start, stop);
    }

    /** Whether something in {@code w} can be seen: it shows, isn't minimised, and anyone can see Unichat. */
    public static boolean seen(Window w) {
        return w != null && w.isShowing() && !(w instanceof Stage st && st.isIconified()) && Seen.get().now();
    }

    /** Runs {@code animation} while it's {@linkplain Animated#want wanted} and {@code node} can be seen; one per node. */
    public static Animated animate(Node node, Animation animation) {
        Animated a = new Animated(node, animation);
        node.getProperties().put(Animated.class, a);
        return a;
    }

    /** Whether the animation {@link #animate} gave {@code node} runs now (for tests: it rests while nobody sees it). */
    public static boolean animating(Node node) {
        return node.getProperties().get(Animated.class) instanceof Animated a && a.running();
    }

    /** How many watches follow {@link Seen} now (tests: a cancelled or hidden one never stays). */
    static int followingSeen() { return SHOWN_WINDOWS.size(); }

    private static void seenChanged() {
        for (Watcher w : new ArrayList<>(SHOWN_WINDOWS)) w.update();
    }

    /** An animation of a node that runs only while it's wanted and the node can be seen. FX thread. */
    public static final class Animated implements Watch {
        private final Animation animation;
        private final Watch watch;
        private boolean wanted, shown;

        private Animated(Node node, Animation animation) {
            this.animation = animation;
            watch = whileShowing(node, () -> { shown = true; follow(); }, () -> { shown = false; follow(); });
        }

        /** Whether it should run when it can be seen (a count going on), or not at all. */
        public void want(boolean on) {
            wanted = on;
            follow();
        }

        boolean running() { return animation.getStatus() == Animation.Status.RUNNING; }

        private void follow() {
            if (!wanted || !shown) animation.stop();
            else if (!running()) animation.play();
        }

        @Override
        public void cancel() {
            watch.cancel();
            want(false);
        }
    }

    private static final class Watcher implements Watch {
        private final Node node;
        private final Runnable start, stop;
        private final ChangeListener<Scene> onScene = (o, a, s) -> scene(s);
        private final ChangeListener<Window> onWindow = (o, a, w) -> window(w);
        private final ChangeListener<Boolean> onChange = (o, a, s) -> update();
        private Scene scene;
        private Window window;
        private boolean shown;

        Watcher(Node node, Runnable start, Runnable stop) {
            this.node = node;
            this.start = start;
            this.stop = stop;
            node.sceneProperty().addListener(onScene);
            scene(node.getScene());
        }

        private void scene(Scene s) {
            if (scene != null) scene.windowProperty().removeListener(onWindow);
            scene = s;
            if (s != null) s.windowProperty().addListener(onWindow);
            window(s == null ? null : s.getWindow());
        }

        private void window(Window w) {
            if (window != null) {
                window.showingProperty().removeListener(onChange);
                if (window instanceof Stage st) st.iconifiedProperty().removeListener(onChange);
            }
            window = w;
            if (w != null) {
                w.showingProperty().addListener(onChange);
                if (w instanceof Stage st) st.iconifiedProperty().addListener(onChange);
            }
            update();
        }

        private void update() {
            if (window != null && window.isShowing()) SHOWN_WINDOWS.add(this);
            else SHOWN_WINDOWS.remove(this);
            boolean now = seen(window);
            if (now == shown) return;
            shown = now;
            (now ? start : stop).run();
        }

        @Override
        public void cancel() {
            node.sceneProperty().removeListener(onScene);
            scene(null);
        }
    }
}

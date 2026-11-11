package app.unichat.ui;

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * Work a node does only while it can be seen: in a scene whose window shows. Animations used to stop when their node
 * left its scene, but a closed window keeps its scene, so a GIF or sticker in a photo viewer, the typing dots of a
 * closed window, went on running for good: held by JavaFX's timer with all their window, and holding their share of
 * the animation budget.
 */
public final class Showing {
    private Showing() {}

    /** What {@link #whileShowing} returns: stops watching (a node handed another animation). */
    public interface Watch {
        void cancel();
    }

    /** Runs {@code start} each time {@code node} comes to be shown and {@code stop} each time it stops being. */
    public static Watch whileShowing(Node node, Runnable start, Runnable stop) {
        return new Watcher(node, start, stop);
    }

    private static final class Watcher implements Watch {
        private final Node node;
        private final Runnable start, stop;
        private final ChangeListener<Scene> onScene = (o, a, s) -> scene(s);
        private final ChangeListener<Window> onWindow = (o, a, w) -> window(w);
        private final ChangeListener<Boolean> onShowing = (o, a, s) -> update();
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
            if (window != null) window.showingProperty().removeListener(onShowing);
            window = w;
            if (w != null) w.showingProperty().addListener(onShowing);
            update();
        }

        private void update() {
            boolean now = window != null && window.isShowing();
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

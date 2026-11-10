package app.unichat.ui.media;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;

import java.util.function.Consumer;

/** The photo in the viewer: wheel or pinch zooms, dragging moves it, a double click toggles its real size. */
final class PhotoPane extends Pane {
    private static final double WHEEL_SPEED = 1.003;
    static final double STEP = 1.25;

    private final ImageView view = new ImageView();
    private final PhotoZoom zoom = new PhotoZoom();
    private final ReadOnlyIntegerWrapper percent = new ReadOnlyIntegerWrapper(100);
    private double lastX;
    private double lastY;

    /** {@code close}: a plain click beside the picture. */
    PhotoPane(String description, Runnable close) {
        view.setManaged(false);
        view.setAccessibleText(description);
        getChildren().add(view);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        widthProperty().addListener((o, a, b) -> change(z -> z.resize(getWidth(), getHeight())));
        heightProperty().addListener((o, a, b) -> change(z -> z.resize(getWidth(), getHeight())));
        // Every frame of an animation arrives here too: only another size is a new picture.
        view.imageProperty().addListener((o, old, img) -> {
            if (img != null && (old == null || old.getWidth() != img.getWidth() || old.getHeight() != img.getHeight())) {
                change(z -> z.setImage(img.getWidth(), img.getHeight()));
            }
        });
        setOnMousePressed(this::press);
        setOnMouseDragged(this::drag);
        setOnMouseClicked(e -> click(e, close));
        setOnScroll(this::scroll);
        setOnZoom(this::pinch);
    }

    ImageView view() {
        return view;
    }

    PhotoZoom zoom() {
        return zoom;
    }

    ReadOnlyIntegerProperty percentProperty() {
        return percent.getReadOnlyProperty();
    }

    /** Applies a change to the zoom and redraws. */
    void change(Consumer<PhotoZoom> what) {
        what.accept(zoom);
        view.setFitWidth(zoom.width());
        view.setFitHeight(zoom.height());
        view.setLayoutX(zoom.left());
        view.setLayoutY(zoom.top());
        view.setRotate(zoom.rotation());
        percent.set(zoom.percent());
        setCursor(zoom.canPan() ? Cursor.OPEN_HAND : Cursor.DEFAULT);
    }

    private void press(MouseEvent e) {
        lastX = e.getSceneX();
        lastY = e.getSceneY();
    }

    private void drag(MouseEvent e) {
        double dx = e.getSceneX() - lastX;
        double dy = e.getSceneY() - lastY;
        press(e);
        change(z -> z.pan(dx, dy));
    }

    private void click(MouseEvent e, Runnable close) {
        if (!e.isStillSincePress()) return; // the end of a drag
        if (e.getTarget() == this && e.getClickCount() == 1) close.run();
        if (e.getTarget() == view && e.getClickCount() == 2) {
            Point2D at = sceneToLocal(e.getSceneX(), e.getSceneY());
            change(z -> z.toggle(at.getX(), at.getY()));
        }
    }

    private void scroll(ScrollEvent e) {
        // A trackpad keeps sending scrolls after the fingers lift, and the picture would keep growing.
        if (e.getDeltaY() == 0 || e.isInertia()) return;
        Point2D at = sceneToLocal(e.getSceneX(), e.getSceneY());
        change(z -> z.zoomAt(Math.pow(WHEEL_SPEED, e.getDeltaY()), at.getX(), at.getY()));
        e.consume();
    }

    private void pinch(ZoomEvent e) {
        Point2D at = sceneToLocal(e.getSceneX(), e.getSceneY());
        change(z -> z.zoomAt(e.getZoomFactor(), at.getX(), at.getY()));
        e.consume();
    }
}

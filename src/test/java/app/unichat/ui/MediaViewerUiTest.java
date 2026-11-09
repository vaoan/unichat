package app.unichat.ui;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The photo window: its keys, wheel, drag and buttons, and when it closes. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class MediaViewerUiTest {
    @TempDir
    Path dir;
    private Stage owner;
    private int saves;

    @BeforeAll
    static void toolkit() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false); // these tests close every window
    }

    @BeforeEach
    void open() throws Exception {
        Path png = dir.resolve("foto.png");
        ImageIO.write(new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        fx(() -> {
            owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 1000, 800));
            owner.show();
            MediaViewer.show(owner, CompletableFuture.completedFuture(png), "Foto", () -> saves++);
            return null;
        });
        waitFor(() -> fx(() -> viewer().isPresent() && photo().getWidth() > 0 && photo().view().getImage() != null));
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            viewer().ifPresent(Window::hide);
            owner.close();
            return null;
        });
    }

    @Test
    void aTallPictureLeavesRoomForTheControls() throws Exception {
        Path tall = dir.resolve("alta.png");
        ImageIO.write(new BufferedImage(300, 5000, BufferedImage.TYPE_INT_RGB), "png", tall.toFile());
        fx(() -> {
            viewer().ifPresent(Window::hide);
            MediaViewer.show(owner, CompletableFuture.completedFuture(tall), "Alta", () -> saves++);
            return null;
        });
        FxWait.until("the tall picture shows", () -> viewer().isPresent() && photo().view().getImage() != null
                && photo().view().getImage().getHeight() > 1000);
        FxWait.settled();
        String where = fx(() -> {
            javafx.scene.Node bar = viewer().orElseThrow().getScene().getRoot().lookup(".media-viewer-bar");
            javafx.geometry.Bounds b = bar.localToScene(bar.getBoundsInLocal());
            return b.getMinY() + ".." + b.getMaxY() + " in " + viewer().orElseThrow().getScene().getHeight();
        });
        assertTrue(fx(() -> {
            javafx.scene.Node bar = viewer().orElseThrow().getScene().getRoot().lookup(".media-viewer-bar");
            javafx.geometry.Bounds b = bar.localToScene(bar.getBoundsInLocal());
            return b.getHeight() > 0 && b.getMaxY() <= viewer().orElseThrow().getScene().getHeight() + 0.5;
        }), "the controls are inside the window: " + where);
    }

    @Test
    void keysDriveTheZoomAndTheTurn() throws Exception {
        double fit = fx(() -> photo().zoom().scale());
        key("+", KeyCode.PLUS);
        assertEquals(fit * 1.25, (double) fx(() -> photo().zoom().scale()), 1e-6);
        key("0", KeyCode.DIGIT0);
        assertEquals(fit, (double) fx(() -> photo().zoom().scale()), 1e-6);
        key("1", KeyCode.DIGIT1);
        assertEquals(100, (int) fx(() -> photo().zoom().percent()));
        key("r", KeyCode.R);
        assertEquals(90.0, (double) fx(() -> photo().view().getRotate()));
        assertTrue(fx(() -> viewer().isPresent()));
        key("", KeyCode.ESCAPE);
        assertFalse(fx(() -> viewer().isPresent()));
    }

    @Test
    void theArrowsStepThroughTheChatsPictures() throws Exception {
        fx(() -> { viewer().ifPresent(Window::hide); return null; });
        java.util.List<MediaViewer.Item> items = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Path f = dir.resolve("foto" + i + ".png");
            ImageIO.write(new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB), "png", f.toFile());
            items.add(new MediaViewer.Item("foto" + i, () -> CompletableFuture.completedFuture(f), () -> saves++));
        }
        fx(() -> { MediaViewer.show(owner, items, 1); return null; });
        waitFor(() -> fx(() -> viewer().isPresent() && photo().view().getImage() != null));
        assertEquals("foto2", fx(() -> ((Stage) viewer().orElseThrow()).getTitle()), "opens on the one clicked");

        key("", KeyCode.RIGHT);
        waitFor(() -> fx(() -> "foto3".equals(((Stage) viewer().orElseThrow()).getTitle()) && photo().view().getImage() != null));
        key("", KeyCode.RIGHT);
        assertEquals("foto3", fx(() -> ((Stage) viewer().orElseThrow()).getTitle()), "stays on the last one");
        key("", KeyCode.LEFT);
        key("", KeyCode.LEFT);
        assertEquals("foto1", fx(() -> ((Stage) viewer().orElseThrow()).getTitle()));

        key("1", KeyCode.DIGIT1); // zoomed in: the arrows pan the picture instead
        waitFor(() -> fx(() -> photo().view().getImage() != null));
        key("", KeyCode.RIGHT);
        assertEquals("foto1", fx(() -> ((Stage) viewer().orElseThrow()).getTitle()));
        key("", KeyCode.PAGE_DOWN);
        assertEquals("foto2", fx(() -> ((Stage) viewer().orElseThrow()).getTitle()), "Page Down always goes on");
        waitFor(() -> fx(() -> photo().view().getImage() != null && photo().zoom().isFitted()));

        // Going full screen for real is a macOS animation a test can't wait on; the button and its name are checked here.
        assertTrue(fx(() -> buttons(viewer().orElseThrow().getScene().getRoot()).stream()
                .anyMatch(b -> "Pantalla completa (F)".equals(b.getAccessibleText()))));
    }

    @Test
    void theWheelZoomsAndTheNodeFollows() throws Exception {
        double before = fx(() -> photo().view().getFitWidth());
        fx(() -> {
            Event.fireEvent(photo(), new ScrollEvent(ScrollEvent.SCROLL, 100, 100, 100, 100, false, false, false, false,
                    false, false, 0, 120, 0, 120, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            return null;
        });
        assertTrue((double) fx(() -> photo().view().getFitWidth()) > before);
    }

    @Test
    void draggingMovesThePictureAndDoesNotCloseTheWindow() throws Exception {
        key("1", KeyCode.DIGIT1);
        double left = fx(() -> photo().view().getLayoutX());
        fx(() -> {
            PhotoPane pane = photo();
            fire(pane, MouseEvent.MOUSE_PRESSED, 300, 1, true);
            fire(pane, MouseEvent.MOUSE_DRAGGED, 250, 1, false);
            fire(pane, MouseEvent.MOUSE_RELEASED, 250, 1, false);
            fire(pane, MouseEvent.MOUSE_CLICKED, 250, 1, false);
            return null;
        });
        assertEquals(left - 50, (double) fx(() -> photo().view().getLayoutX()), 1e-6);
        assertTrue(fx(() -> viewer().isPresent()), "the end of a drag is no click beside the picture");
    }

    @Test
    void aClickBesideThePictureClosesAndOnItDoesNot() throws Exception {
        fx(() -> {
            fire(photo().view(), MouseEvent.MOUSE_CLICKED, 300, 1, true);
            return null;
        });
        assertTrue(fx(() -> viewer().isPresent()));
        fx(() -> {
            fire(photo(), MouseEvent.MOUSE_CLICKED, 1, 1, true);
            return null;
        });
        assertFalse(fx(() -> viewer().isPresent()));
    }

    @Test
    void aDoubleClickTogglesRealSize() throws Exception {
        fx(() -> {
            fire(photo().view(), MouseEvent.MOUSE_CLICKED, 300, 2, true);
            return null;
        });
        assertEquals(100, (int) fx(() -> photo().zoom().percent()));
        fx(() -> {
            fire(photo().view(), MouseEvent.MOUSE_CLICKED, 300, 2, true);
            return null;
        });
        assertTrue(fx(() -> photo().zoom().isFitted()));
    }

    @Test
    void everyButtonHasAnAccessibleNameOrTextAndSaveCalls() throws Exception {
        List<Button> buttons = fx(() -> buttons(viewer().orElseThrow().getScene().getRoot()));
        assertEquals(9, buttons.size(), "zoom, fit, 100 %, turn, save, full screen, and before/after");
        for (Button b : buttons) {
            assertTrue(b.getAccessibleText() != null || !b.getText().isEmpty(), String.valueOf(b));
        }
        fx(() -> {
            buttons.stream().filter(b -> "Guardar como…".equals(b.getText())).findFirst().orElseThrow().fire();
            return null;
        });
        assertEquals(1, saves);
    }

    private static List<Button> buttons(Node n) {
        List<Button> found = new ArrayList<>();
        if (n instanceof Button b) found.add(b);
        if (n instanceof javafx.scene.Parent p) p.getChildrenUnmodifiable().forEach(c -> found.addAll(buttons(c)));
        return found;
    }

    private static void fire(EventTarget target, EventType<MouseEvent> type, double x, int clicks, boolean still) {
        Event.fireEvent(target, new MouseEvent(type, x, 100, x, 100, MouseButton.PRIMARY, clicks, false, false, false, false,
                true, false, false, false, false, still, null));
    }

    private void key(String text, KeyCode code) throws Exception {
        fx(() -> {
            Event.fireEvent(viewer().orElseThrow().getScene(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, text, code, false, false, false, false));
            return null;
        });
    }

    private static Optional<Window> viewer() {
        return Window.getWindows().stream().filter(w -> w.getScene() != null
                && w.getScene().getRoot().getStyleClass().contains("media-viewer")).findFirst();
    }

    private static PhotoPane photo() {
        return (PhotoPane) viewer().orElseThrow().getScene().getRoot().lookupAll("*").stream()
                .filter(PhotoPane.class::isInstance).findFirst().orElseThrow();
    }

    private static void waitFor(java.util.concurrent.Callable<Boolean> done) throws Exception {
        try {
            FxWait.until("the photo showed", done); // a throw (the picture isn't in the window yet) counts as not yet
        } catch (AssertionError timedOut) {
            Thread.interrupted(); // so the windows can still be asked what they show
            throw new AssertionError(timedOut.getMessage() + "; windows: " + windows(), timedOut);
        }
    }

    private static List<String> windows() throws Exception {
        return fx(() -> Window.getWindows().stream()
                .map(w -> w.getClass().getSimpleName() + (w.getScene() == null ? "" : ":" + w.getScene().getRoot().getStyleClass()
                        + (w.getScene().getRoot().lookupAll("*").stream().filter(PhotoPane.class::isInstance).findFirst()
                                .map(n -> " photo w=" + ((PhotoPane) n).getWidth() + " img=" + ((PhotoPane) n).view().getImage()
                                        + " area=" + n.getParent()).orElse(" no photo pane; shows " + w.getScene().getRoot().lookupAll(".label, .progress-indicator").stream()
                                        .map(n -> n instanceof javafx.scene.control.Label l ? "label:" + l.getText() : "spinner").toList()))))
                .toList());
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }
}

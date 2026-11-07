package app.unichat.ui;

import app.unichat.support.I18n;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Full-size image viewer window with zoom, turn and save controls; Esc or a click beside the picture closes it. It
 * opens at once with a spinner while the full image downloads (a click that showed nothing for seconds looked like
 * it didn't work), and plays animated WebP too.
 */
public final class MediaViewer {
    private static final double ARROW_STEP = 60;

    private MediaViewer() {}

    /** {@code onSave}: the "Guardar como…" button. */
    public static void show(Window owner, CompletableFuture<Path> file, String title, Runnable onSave) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(40, 40);
        StackPane area = new StackPane(spin);
        Stage stage = new Stage();
        String name = title == null ? I18n.t("media.image") : title;
        PhotoPane photo = new PhotoPane(name, stage::close);
        FlowPane bar = bar(photo, onSave);
        Nodes.show(bar, false);
        BorderPane root = new BorderPane(area);
        root.setBottom(bar);
        root.getStyleClass().add("media-viewer");
        stage.initOwner(owner);
        stage.setTitle(name);
        Scene scene = new Scene(root, Math.min(900, owner.getWidth() * 0.8), Math.min(700, owner.getHeight() * 0.8));
        Theme.register(scene);
        // A filter: the arrows would otherwise move the focus between the buttons.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
            else if (bar.isVisible() && key(photo, e)) e.consume();
        });
        area.setOnMouseClicked(e -> { if (e.getClickCount() == 1 && e.getTarget() == area) stage.close(); });
        stage.setScene(scene);
        stage.show();

        file.whenComplete((p, err) -> {
            try {
                if (err != null || p == null) throw new IOException("no file", err);
                load(p, () -> {
                    area.getChildren().setAll(photo);
                    Nodes.show(bar, true);
                }, photo);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Label l = new Label(I18n.t("viewer.openFailed"));
                    l.getStyleClass().add("settings-help");
                    area.getChildren().setAll(l);
                });
            }
        });
    }

    /** Decodes here, off the UI thread, then shows it. */
    private static void load(Path p, Runnable reveal, PhotoPane photo) throws IOException {
        if (AnimatedImage.isWebp(p) || AnimatedImage.isGif(p)) {
            // WebP JavaFX can't read; GIFs it animates forever, leaking every frame (AnimatedImage).
            AnimatedImage.Frames frames = AnimatedImage.decode(p, 1600, 1600);
            Platform.runLater(() -> {
                reveal.run();
                AnimatedImage.play(photo.view(), frames);
            });
        } else {
            Image img = new Image(p.toUri().toString());
            if (img.isError()) throw new IOException("unreadable image", img.getException());
            Platform.runLater(() -> {
                reveal.run();
                photo.view().setImage(img);
            });
        }
    }

    private static FlowPane bar(PhotoPane photo, Runnable onSave) {
        Label percent = new Label();
        percent.getStyleClass().add("media-viewer-percent");
        percent.textProperty().bind(Bindings.format("%d %%", photo.percentProperty()));
        Button save = new Button(I18n.t("message.saveAs"));
        save.getStyleClass().add("secondary-button");
        save.setOnAction(e -> onSave.run());
        FlowPane bar = new FlowPane(6, 6,
                Icons.iconButton(Icons.MINUS, I18n.t("viewer.zoomOut"), () -> photo.change(z -> z.zoomBy(1 / PhotoPane.STEP))),
                percent,
                Icons.iconButton(Icons.PLUS, I18n.t("viewer.zoomIn"), () -> photo.change(z -> z.zoomBy(PhotoPane.STEP))),
                textButton(I18n.t("viewer.fit"), I18n.t("viewer.fitHelp"), () -> photo.change(PhotoZoom::fit)),
                textButton("100 %", I18n.t("viewer.actualSize"), () -> photo.change(PhotoZoom::actualSize)),
                Icons.iconButton(Icons.ROTATE, I18n.t("viewer.rotate"), () -> photo.change(PhotoZoom::rotate)),
                save);
        bar.setAlignment(Pos.CENTER);
        bar.setRowValignment(javafx.geometry.VPos.CENTER);
        bar.getStyleClass().add("media-viewer-bar");
        return bar;
    }

    private static Button textButton(String text, String help, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("secondary-button");
        b.setTooltip(new Tooltip(help));
        b.setAccessibleText(help);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** True if the key was one of the viewer's. By character for + and -, which move around between keyboards. */
    private static boolean key(PhotoPane photo, KeyEvent e) {
        switch (e.getCode()) {
            case LEFT -> photo.change(z -> z.pan(ARROW_STEP, 0));
            case RIGHT -> photo.change(z -> z.pan(-ARROW_STEP, 0));
            case UP -> photo.change(z -> z.pan(0, ARROW_STEP));
            case DOWN -> photo.change(z -> z.pan(0, -ARROW_STEP));
            case ADD -> photo.change(z -> z.zoomBy(PhotoPane.STEP));
            case SUBTRACT -> photo.change(z -> z.zoomBy(1 / PhotoPane.STEP));
            default -> {
                switch (e.getText().toLowerCase()) {
                    case "+", "=" -> photo.change(z -> z.zoomBy(PhotoPane.STEP));
                    case "-" -> photo.change(z -> z.zoomBy(1 / PhotoPane.STEP));
                    case "0" -> photo.change(PhotoZoom::fit);
                    case "1" -> photo.change(PhotoZoom::actualSize);
                    case "r" -> photo.change(PhotoZoom::rotate);
                    default -> { return false; }
                }
            }
        }
        return true;
    }
}

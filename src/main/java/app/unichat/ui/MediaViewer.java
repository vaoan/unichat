package app.unichat.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Full-size image viewer window; Esc or click closes it. It opens at once with a spinner while the full image
 * downloads (a click that showed nothing for seconds looked like it didn't work), and plays animated WebP too.
 */
public final class MediaViewer {
    private MediaViewer() {}

    public static void show(Window owner, CompletableFuture<Path> file, String title) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(40, 40);
        StackPane root = new StackPane(spin);
        root.getStyleClass().add("media-viewer");
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle(title == null ? "Imagen" : title);
        Scene scene = new Scene(root, Math.min(900, owner.getWidth() * 0.8), Math.min(700, owner.getHeight() * 0.8));
        Theme.register(scene);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.close(); });
        root.setOnMouseClicked(e -> { if (e.getClickCount() == 1 && e.getTarget() == root) stage.close(); });
        stage.setScene(scene);
        stage.show();

        file.whenComplete((p, err) -> {
            if (err != null || p == null) {
                Platform.runLater(() -> {
                    Label l = new Label("No se pudo abrir la imagen.");
                    l.getStyleClass().add("settings-help");
                    root.getChildren().setAll(l);
                });
                return;
            }
            try {
                ImageView view = new ImageView();
                view.setPreserveRatio(true);
                view.fitWidthProperty().bind(root.widthProperty().subtract(40));
                view.fitHeightProperty().bind(root.heightProperty().subtract(40));
                if (AnimatedImage.isWebp(p)) {
                    // JavaFX can't read WebP at all: decode (and animate) it ourselves.
                    AnimatedImage.Frames frames = AnimatedImage.decodeWebp(p, 1600, 1600);
                    Platform.runLater(() -> {
                        root.getChildren().setAll(view);
                        AnimatedImage.play(view, frames);
                    });
                } else {
                    Image img = new Image(p.toUri().toString());
                    Platform.runLater(() -> {
                        view.setImage(img);
                        root.getChildren().setAll(view);
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> root.getChildren().setAll(new Label("No se pudo abrir la imagen.")));
            }
        });
    }
}

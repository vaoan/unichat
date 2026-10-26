package app.unichat.ui;

import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;

/** Full-size image viewer window; Esc or click closes it. */
public final class MediaViewer {
    private MediaViewer() {}

    public static void show(Window owner, Path file, String title) {
        Image img = new Image(file.toUri().toString());
        ImageView view = new ImageView(img);
        view.setPreserveRatio(true);
        StackPane root = new StackPane(view);
        root.getStyleClass().add("media-viewer");
        double w = Math.min(img.getWidth(), owner.getWidth() * 0.9);
        double h = Math.min(img.getHeight(), owner.getHeight() * 0.9);
        view.fitWidthProperty().bind(root.widthProperty().subtract(40));
        view.fitHeightProperty().bind(root.heightProperty().subtract(40));
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle(title == null ? "Imagen" : title);
        Scene scene = new Scene(root, Math.max(320, w + 40), Math.max(240, h + 40));
        Theme.register(scene);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.close(); });
        root.setOnMouseClicked(e -> { if (e.getClickCount() == 1 && e.getTarget() == root) stage.close(); });
        stage.setScene(scene);
        stage.show();
    }
}

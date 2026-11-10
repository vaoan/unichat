package app.unichat.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import app.unichat.support.I18n;
import app.unichat.video.VideoTranscoder;
import app.unichat.video.WebmStickers;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Video window: opens at once with a spinner while the file downloads, and plays it with its controls
 * ({@link VideoControls}: the keys too). Esc leaves full screen, then closes. What JavaFX can't play goes through ffmpeg; failing that, it offers the
 * system's player.
 */
final class VideoViewer {
    static final String PLAYER = "video-player";
    private static final int MAX_PICTURE = 1600;

    private final Stage stage = new Stage();
    private final StackPane screen = new StackPane();
    private final Consumer<Path> openElsewhere;
    private MediaPlayer player;
    private VideoControls controls;
    private Runnable onSave;

    private VideoViewer(Consumer<Path> openElsewhere) {
        this.openElsewhere = openElsewhere;
    }

    /**
     * @param onSave        "Guardar como…" button (null: none)
     * @param openElsewhere opens the file in the system's player when this one can't
     */
    static Stage show(Window owner, CompletableFuture<Path> file, String title, Runnable onSave, Consumer<Path> openElsewhere) {
        VideoViewer v = new VideoViewer(openElsewhere);
        v.open(owner, title, onSave);
        file.whenComplete((p, err) -> Platform.runLater(() -> {
            if (!v.stage.isShowing()) return;
            if (err != null || p == null) v.fail(I18n.t("video.downloadFailed"), null);
            else v.play(p);
        }));
        return v.stage;
    }

    private void open(Window owner, String title, Runnable onSave) {
        this.onSave = onSave;
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(40, 40);
        screen.getChildren().add(spin);
        VBox.setVgrow(screen, Priority.ALWAYS);
        // The video's size follows the screen's, so the screen must not take its size from the video: a phone video
        // taller than the window held it at the video's height and pushed the controls out of the window.
        screen.setMinSize(0, 0);
        BorderPane root = new BorderPane(screen);
        root.getStyleClass().add("media-viewer");
        // Not owned by the main window: macOS gives an owned window no full screen of its own, and JavaFX hung on
        // setFullScreen. Its own window, then, with the app's icon (Windows shows it in the taskbar).
        if (owner instanceof Stage main) stage.getIcons().setAll(main.getIcons());
        stage.setTitle(title == null ? I18n.t("video.title") : title);
        Scene scene = new Scene(root, Math.min(900, owner.getWidth() * 0.8), Math.min(700, owner.getHeight() * 0.8));
        Theme.register(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        stage.setScene(scene);
        stage.setOnHidden(e -> release());
        stage.show();
    }

    /** What the file is, not what it was called: bridges can deliver a GIF for something labelled as a video. */
    private void play(Path file) {
        WebmStickers.WORKER.execute(() -> {
            boolean picture;
            try {
                picture = AnimatedImage.isGif(file) || AnimatedImage.isWebp(file);
            } catch (IOException e) {
                picture = false;
            }
            if (picture) showPicture(file);
            else Platform.runLater(() -> playVideo(file, true));
        });
    }

    private void showPicture(Path file) {
        try {
            AnimatedImage.Frames frames = AnimatedImage.decode(file, MAX_PICTURE, MAX_PICTURE);
            Platform.runLater(() -> {
                if (!stage.isShowing()) return;
                ImageView view = new ImageView();
                view.setPreserveRatio(true);
                view.fitWidthProperty().bind(screen.widthProperty());
                view.fitHeightProperty().bind(screen.heightProperty());
                screen.getChildren().setAll(view);
                AnimatedImage.play(view, frames);
            });
        } catch (IOException e) {
            fail(I18n.t("video.openFailed"), file);
        }
    }

    /** @param mayConvert on failure, try once more through ffmpeg */
    private void playVideo(Path file, boolean mayConvert) {
        player = MediaPlayers.open(file, () -> {
            if (!stage.isShowing()) return;
            release();
            if (mayConvert) convertAndPlay(file);
            else fail(I18n.t("video.unsupported"), file);
        });
        if (player == null) return;
        stage.getProperties().put(PLAYER, player);
        MediaView view = new MediaView(player);
        view.setPreserveRatio(true);
        view.fitWidthProperty().bind(screen.widthProperty());
        view.fitHeightProperty().bind(screen.heightProperty());
        view.setAccessibleText(I18n.t("video.title"));
        screen.getChildren().setAll(view);
        controls = new VideoControls(player, stage, view, screen, onSave, () -> openElsewhere.accept(file));
        ((BorderPane) stage.getScene().getRoot()).setBottom(controls);
        player.setAutoPlay(true);
    }

    private void convertAndPlay(Path file) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(40, 40);
        Label what = new Label(I18n.t("video.preparing"));
        what.getStyleClass().add("video-message");
        VBox box = new VBox(12, spin, what);
        box.setAlignment(Pos.CENTER);
        screen.getChildren().setAll(box);
        ((BorderPane) stage.getScene().getRoot()).setBottom(null);
        WebmStickers.WORKER.execute(() -> {
            try {
                Path playable = VideoTranscoder.toPlayable(file, done ->
                        Platform.runLater(() -> what.setText(I18n.t("video.installingConverter", (int) (done * 100)))));
                Platform.runLater(() -> {
                    if (stage.isShowing()) playVideo(playable, false);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                fail(I18n.t("video.unsupported"), file);
            }
        });
    }

    /** Esc leaves full screen, then closes; the rest are the player's ({@link VideoControls#key}). */
    private void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            if (stage.isFullScreen()) stage.setFullScreen(false);
            else stage.close();
            e.consume();
        } else if (controls != null && player != null && controls.key(e)) {
            e.consume();
        }
    }

    private void fail(String message, Path file) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> fail(message, file));
            return;
        }
        release();
        ((BorderPane) stage.getScene().getRoot()).setBottom(null);
        Label l = new Label(message);
        l.getStyleClass().add("video-message");
        l.setWrapText(true);
        VBox box = new VBox(12, l);
        box.setAlignment(Pos.CENTER);
        if (file != null) {
            Button elsewhere = new Button(I18n.t("video.openElsewhere"));
            elsewhere.getStyleClass().add("primary-button");
            elsewhere.setOnAction(e -> {
                openElsewhere.accept(file);
                stage.close();
            });
            box.getChildren().add(elsewhere);
        }
        screen.getChildren().setAll(box);
    }

    private void release() {
        controls = null;
        if (player == null) return;
        player.dispose();
        player = null;
    }
}

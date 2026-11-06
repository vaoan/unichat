package app.unichat.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import app.unichat.video.VideoTranscoder;
import app.unichat.video.WebmStickers;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Video window: opens at once with a spinner while the file downloads, and plays it with its controls. Space plays or
 * pauses, the arrows move 5 s, M mutes, Esc closes. What JavaFX can't play goes through ffmpeg; failing that, it offers the
 * system's player.
 */
final class VideoViewer {
    private static final double SEEK_SECONDS = 5;
    static final String PLAYER = "video-player";
    private static final int MAX_PICTURE = 1600;

    private final Stage stage = new Stage();
    private final StackPane screen = new StackPane();
    private final Consumer<Path> openElsewhere;
    private MediaPlayer player;

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
            if (err != null || p == null) v.fail("No se pudo descargar el video.", null);
            else v.play(p);
        }));
        return v.stage;
    }

    /** "1:05", or "1:02:05" past an hour. */
    static String clock(double seconds) {
        long s = Double.isNaN(seconds) || seconds < 0 ? 0 : (long) seconds;
        String ms = String.format("%02d:%02d", s / 60 % 60, s % 60);
        return s >= 3600 ? (s / 3600) + ":" + ms : ms.startsWith("0") ? ms.substring(1) : ms;
    }

    private void open(Window owner, String title, Runnable onSave) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(40, 40);
        screen.getChildren().add(spin);
        VBox.setVgrow(screen, Priority.ALWAYS);
        BorderPane root = new BorderPane(screen);
        root.getStyleClass().add("media-viewer");
        if (onSave != null) {
            Button save = new Button("Guardar como…");
            save.getStyleClass().add("secondary-button");
            save.setOnAction(e -> onSave.run());
            HBox top = new HBox(save);
            top.setAlignment(Pos.CENTER_RIGHT);
            top.getStyleClass().add("video-top");
            root.setTop(top);
        }
        stage.initOwner(owner);
        stage.setTitle(title == null ? "Video" : title);
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
            AnimatedImage.Frames frames = AnimatedImage.isGif(file) ? AnimatedImage.decodeGif(file, MAX_PICTURE, MAX_PICTURE)
                    : AnimatedImage.decodeWebp(file, MAX_PICTURE, MAX_PICTURE);
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
            fail("No se pudo abrir el archivo.", file);
        }
    }

    /** @param mayConvert on failure, try once more through ffmpeg */
    private void playVideo(Path file, boolean mayConvert) {
        player = MediaPlayers.open(file, () -> {
            if (!stage.isShowing()) return;
            release();
            if (mayConvert) convertAndPlay(file);
            else fail("Este formato de video no se puede reproducir aquí.", file);
        });
        if (player == null) return;
        stage.getProperties().put(PLAYER, player);
        MediaView view = new MediaView(player);
        view.setPreserveRatio(true);
        view.fitWidthProperty().bind(screen.widthProperty());
        view.fitHeightProperty().bind(screen.heightProperty());
        view.setAccessibleText("Video");
        screen.getChildren().setAll(view);
        ((BorderPane) stage.getScene().getRoot()).setBottom(controls(player));
        player.setAutoPlay(true);
    }

    private void convertAndPlay(Path file) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(40, 40);
        Label what = new Label("Preparando el video…");
        what.getStyleClass().add("video-message");
        VBox box = new VBox(12, spin, what);
        box.setAlignment(Pos.CENTER);
        screen.getChildren().setAll(box);
        ((BorderPane) stage.getScene().getRoot()).setBottom(null);
        WebmStickers.WORKER.execute(() -> {
            try {
                Path playable = VideoTranscoder.toPlayable(file, done ->
                        Platform.runLater(() -> what.setText("Instalando el conversor… " + (int) (done * 100) + " %")));
                Platform.runLater(() -> {
                    if (stage.isShowing()) playVideo(playable, false);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                fail("Este formato de video no se puede reproducir aquí.", file);
            }
        });
    }

    private HBox controls(MediaPlayer p) {
        Button toggle = Composer.iconButton(Icons.PAUSE, "Pausar", this::togglePlay);
        p.statusProperty().addListener((o, a, st) -> {
            boolean playing = st == MediaPlayer.Status.PLAYING;
            toggle.setGraphic(Icons.icon(playing ? Icons.PAUSE : Icons.PLAY, 18));
            toggle.setAccessibleText(playing ? "Pausar" : "Reproducir");
        });
        Slider position = new Slider(0, 1, 0);
        position.setAccessibleText("Posición del video");
        position.setFocusTraversable(true);
        HBox.setHgrow(position, Priority.ALWAYS);
        Label time = new Label(clock(0));
        time.getStyleClass().add("video-time");
        time.setMinWidth(Region.USE_PREF_SIZE);
        p.currentTimeProperty().addListener((o, a, now) -> {
            double total = p.getTotalDuration().toSeconds();
            time.setText(clock(now.toSeconds()) + " / " + clock(total));
            if (!position.isValueChanging() && total > 0) position.setValue(now.toSeconds() / total);
        });
        position.valueChangingProperty().addListener((o, a, moving) -> {
            if (!moving) p.seek(p.getTotalDuration().multiply(position.getValue()));
        });
        Button mute = Composer.iconButton(Icons.VOLUME, "Silenciar", null);
        mute.setOnAction(e -> toggleMute(mute));
        HBox bar = new HBox(10, toggle, position, time, mute);
        bar.setAlignment(Pos.CENTER);
        bar.getStyleClass().add("video-controls");
        return bar;
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getStatus() == MediaPlayer.Status.PLAYING) player.pause();
        else {
            // At the end, play would stay there: start over.
            if (player.getCurrentTime().greaterThanOrEqualTo(player.getTotalDuration())) player.seek(Duration.ZERO);
            player.play();
        }
    }

    private void toggleMute(Button mute) {
        player.setMute(!player.isMute());
        mute.setGraphic(Icons.icon(player.isMute() ? Icons.VOLUME_OFF : Icons.VOLUME, 18));
        mute.setAccessibleText(player.isMute() ? "Activar sonido" : "Silenciar");
    }

    private void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            stage.close();
            e.consume();
        } else if (player != null && e.getCode() == KeyCode.SPACE) {
            togglePlay();
            e.consume();
        } else if (player != null && (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT)) {
            double by = e.getCode() == KeyCode.LEFT ? -SEEK_SECONDS : SEEK_SECONDS;
            player.seek(player.getCurrentTime().add(Duration.seconds(by)));
            e.consume();
        } else if (player != null && e.getCode() == KeyCode.M) {
            player.setMute(!player.isMute());
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
            Button elsewhere = new Button("Abrir con otra app");
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
        if (player == null) return;
        player.dispose();
        player = null;
    }
}

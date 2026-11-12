package app.unichat.ui.media;

import app.unichat.ui.Showing;
import app.unichat.ui.Icons;
import app.unichat.ui.Theme;
import app.unichat.service.MediaSource;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * A GIF that came as a video, playing by itself in the timeline: muted, looping, only while it's on screen. List cells
 * come and go as you scroll, so a player exists only while its node is in the scene (native players are heavy, and GIFs
 * that were never let go of already ran the heap out once). Reduced motion: nothing plays until it's opened.
 */
final class InlineVideo extends StackPane {
    /** A quick scroll past shouldn't download and start every GIF on the way. */
    private static final Duration START_DELAY = Duration.millis(250);

    private final MediaSource svc;
    private final String url;
    private final Supplier<Node> onFailure;
    private final MediaView view = new MediaView();
    private final PauseTransition start = new PauseTransition(START_DELAY);
    private final Timeline visibility = new Timeline(new KeyFrame(Duration.seconds(1), e -> followVisibility()));
    /** Behind a sheet it pauses at once: its frames under the card's shadow made the Mac flash black. */
    private final Runnable coverChanged = this::followVisibility;
    private MediaPlayer player;
    private long generation;

    /** @param onFailure what to show instead when the video can't play (it replaces the video) */
    InlineVideo(MediaSource svc, String url, double width, double height, Supplier<Node> onFailure) {
        this.svc = svc;
        this.url = url;
        this.onFailure = onFailure;
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.setPreserveRatio(true);
        getChildren().add(view);
        visibility.setCycleCount(Timeline.INDEFINITE);
        start.setOnFinished(e -> load());
        if (Theme.reduceMotion()) {
            getChildren().add(Icons.icon(Icons.PLAY, 28));
            return;
        }
        // Its native player only while it can be seen: a closed window keeps its scene, and the video played on.
        Showing.whileShowing(this, start::playFromStart, this::release);
    }

    MediaPlayer player() { return player; }

    private void load() {
        long mine = ++generation;
        svc.media(url, 0, 0).whenComplete((p, err) -> Platform.runLater(() -> {
            if (mine != generation || getScene() == null) return; // released meanwhile
            if (err != null || p == null) failed();
            else play(p);
        }));
    }

    private void play(Path file) {
        long mine = generation;
        player = MediaPlayers.open(file, () -> {
            if (mine == generation) failed();
        });
        if (player == null) return;
        player.setMute(true);
        player.setCycleCount(MediaPlayer.INDEFINITE);
        view.setMediaPlayer(player);
        player.play();
        visibility.playFromStart();
        AnimatedImage.watchCover(coverChanged);
        followVisibility();
    }

    private void followVisibility() {
        if (player == null) return;
        boolean shown = AnimatedImage.seen(this);
        boolean playing = player.getStatus() == MediaPlayer.Status.PLAYING;
        if (shown && !playing) player.play();
        else if (!shown && playing) player.pause();
    }

    private void failed() {
        release();
        getChildren().setAll(onFailure.get());
    }

    private void release() {
        generation++;
        start.stop();
        visibility.stop();
        AnimatedImage.unwatchCover(coverChanged);
        view.setMediaPlayer(null);
        if (player != null) player.dispose();
        player = null;
    }
}

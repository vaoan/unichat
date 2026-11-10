package app.unichat.ui.media;

import javafx.animation.PauseTransition;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.nio.file.Path;

/** Makes a player for a file and says so when it can't play: a damaged file may report nothing and just never get ready. */
final class MediaPlayers {
    private static final Duration READY_WITHIN = Duration.seconds(5);

    private MediaPlayers() {}

    /** @return the player, or null after calling {@code onFailure} (which may also run later, on the FX thread) */
    static MediaPlayer open(Path file, Runnable onFailure) {
        Media media;
        MediaPlayer player;
        try {
            media = new Media(file.toUri().toString());
            player = new MediaPlayer(media);
        } catch (MediaException | IllegalArgumentException e) {
            onFailure.run();
            return null;
        }
        boolean[] told = {false};
        Runnable once = () -> {
            if (told[0]) return;
            told[0] = true;
            onFailure.run();
        };
        media.setOnError(once);
        player.setOnError(once);
        PauseTransition ready = new PauseTransition(READY_WITHIN);
        ready.setOnFinished(e -> {
            MediaPlayer.Status st = player.getStatus();
            // Null, not UNKNOWN, is what a damaged file leaves it at.
            if (st == null || st == MediaPlayer.Status.UNKNOWN) once.run();
        });
        ready.play();
        return player;
    }
}

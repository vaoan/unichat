package app.unichat.ui.media;

import app.unichat.ui.Formats;
import app.unichat.ui.Icons;
import app.unichat.model.Message;
import app.unichat.support.I18n;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.function.Supplier;

/**
 * Play, position and time of one audio, following {@link AudioPlayback}. Listens only while on screen: chat rows are
 * rebuilt as you scroll, and each one left listening would stay in memory for as long as the app runs.
 */
final class AudioControls extends HBox {
    private final AudioPlayback playback;
    private final Supplier<Message> message;
    private final Button toggle;
    private final Slider position = new Slider(0, 1, 0);
    private final Label time = new Label();
    private final Runnable onChange = this::refresh;
    private boolean updating;

    /** @param message what these controls are for (the bar: whatever is playing) */
    AudioControls(AudioPlayback playback, Supplier<Message> message, Runnable onToggle) {
        super(8);
        this.playback = playback;
        this.message = message;
        getStyleClass().add("audio-controls");
        setAlignment(Pos.CENTER_LEFT);
        toggle = Icons.iconButton(Icons.PLAY, I18n.t("audio.play"), onToggle);
        toggle.getStyleClass().add("audio-toggle");
        position.setAccessibleText(I18n.t("audio.position"));
        position.setBlockIncrement(0.05);
        position.setMinWidth(60);
        HBox.setHgrow(position, Priority.ALWAYS);
        position.valueProperty().addListener((o, a, v) -> {
            if (!updating && !position.isValueChanging()) playback.seek(v.doubleValue());
        });
        position.valueChangingProperty().addListener((o, a, moving) -> {
            if (!moving && !updating) playback.seek(position.getValue());
        });
        time.getStyleClass().add("audio-time");
        time.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().addAll(toggle, position, time);
        sceneProperty().addListener((o, a, scene) -> {
            playback.removeListener(onChange);
            if (scene != null) {
                playback.addListener(onChange);
                refresh();
            }
        });
        refresh();
    }

    /** Rows are navigated as a whole in the timeline (the P key plays): tabbing through each message would be unusable. */
    void leaveTabOrder() {
        toggle.setFocusTraversable(false);
        position.setFocusTraversable(false);
    }

    private void refresh() {
        Message m = message.get();
        boolean mine = playback.isCurrent(m);
        AudioPlayback.State st = mine ? playback.state() : null;
        if (st == AudioPlayback.State.LOADING) {
            ProgressIndicator spin = new ProgressIndicator();
            spin.setMaxSize(18, 18);
            toggle.setGraphic(spin);
            toggle.setAccessibleText(I18n.t("audio.loading"));
        } else {
            boolean playing = st == AudioPlayback.State.PLAYING;
            toggle.setGraphic(Icons.icon(playing ? Icons.PAUSE : Icons.PLAY, 18));
            toggle.setAccessibleText(I18n.t(playing ? "audio.pause" : "audio.playAudio"));
        }
        position.setDisable(!mine);
        double total = mine ? playback.duration() : m == null ? 0 : m.durationMs() / 1000.0;
        double now = mine ? playback.position() : 0;
        updating = true;
        if (!position.isValueChanging()) position.setValue(total > 0 ? Math.min(1, now / total) : 0);
        updating = false;
        // A length nobody reported isn't 0:00: that reads as an empty audio.
        String length = total > 0 ? Formats.clock(total) : "";
        time.setText(!mine ? length : total > 0 ? Formats.clock(now) + " / " + length : Formats.clock(now));
    }
}

package app.unichat.ui;

import app.unichat.support.I18n;
import app.unichat.support.Os;
import app.unichat.voice.VoiceRecorder;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.function.Consumer;

/** Takes the composer's place while recording: discard, time, loudness, send. Esc discards. */
final class VoiceBar extends HBox {
    /** Pure silence this long means the system is giving us nothing, not a quiet room. */
    private static final long SILENT_MS = 2_500;

    private final VoiceRecorder recorder;
    private final Timeline ticks;
    private final Label time = new Label("0:00");
    private final ProgressBar level = new ProgressBar(0);
    private final Consumer<String> onNoSound;

    /**
     * @param onNoSound the recording was discarded for bringing no sound; gets what to tell the person
     */
    VoiceBar(VoiceRecorder recorder, Runnable onDiscard, Runnable onSend, Consumer<String> onNoSound) {
        super(8);
        this.recorder = recorder;
        this.onNoSound = onNoSound;
        getStyleClass().add("voice-bar");
        setAlignment(Pos.CENTER_LEFT);
        Button discard = Icons.iconButton(Icons.TRASH, I18n.t("voiceBar.discard"), onDiscard);
        Region dot = new Region();
        dot.getStyleClass().add("voice-dot");
        dot.setMinSize(10, 10);
        dot.setMaxSize(10, 10);
        time.getStyleClass().add("voice-time");
        time.setMinWidth(Region.USE_PREF_SIZE);
        time.setAccessibleText(I18n.t("voiceBar.recording"));
        level.getStyleClass().add("voice-level");
        level.setMaxWidth(Double.MAX_VALUE);
        level.setAccessibleText(I18n.t("voiceBar.level"));
        HBox.setHgrow(level, Priority.ALWAYS);
        Button send = Icons.iconButton(Icons.SEND, I18n.t("voiceBar.send"), onSend);
        send.getStyleClass().add("send-button");
        getChildren().addAll(discard, dot, time, level, send);
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                onDiscard.run();
                e.consume();
            }
        });
        ticks = new Timeline(new KeyFrame(Duration.millis(100), e -> tick()));
        ticks.setCycleCount(Timeline.INDEFINITE);
        ticks.play();
        sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) ticks.stop();
        });
    }

    void focusSend() { getChildren().get(getChildren().size() - 1).requestFocus(); }

    private void tick() {
        time.setText(VideoViewer.clock(recorder.durationMs() / 1000.0));
        level.setProgress(Math.min(1, recorder.level() * 2));
        if (recorder.durationMs() >= SILENT_MS && !recorder.anySound()) {
            ticks.stop();
            onNoSound.accept(I18n.t(Os.mac() ? "voiceBar.silentMac" : "voiceBar.silent"));
        }
    }
}

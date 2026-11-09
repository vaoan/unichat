package app.unichat.ui;

import app.unichat.support.I18n;
import javafx.animation.PauseTransition;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.Locale;

/**
 * The video window's controls, as a desktop player has them: play, ten seconds back and forward, position, volume,
 * speed, repeat, see it again at the end, full screen (controls hide while it plays), always on top, save the video or
 * the frame on screen, and the system's player. The keys are YouTube's (see {@link #key}).
 */
final class VideoControls extends VBox {
    static final double[] RATES = {0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2};
    private static final double SKIP = 10;
    private static final double ARROW_SKIP = 5;
    private static final double FRAME = 1.0 / 30;
    /** Volume and speed carry over to the next video while Unichat is open. */
    private static double lastVolume = 1;
    private static double lastRate = 1;

    private final MediaPlayer p;
    private final Stage stage;
    private final MediaView view;
    private final StackPane screen;
    private final Button toggle;
    private final Button mute;
    private final Button repeat;
    private final Button full;
    private final Button onTop;
    private final Slider volume = new Slider(0, 1, lastVolume);
    private final MenuButton speed = new MenuButton();
    private final ToggleGroup rates = new ToggleGroup();
    private final Node replay;
    private final PauseTransition idle = new PauseTransition(Duration.seconds(2.5));
    private boolean ended;

    /** @param onSave saves the video (null: no button); {@code openElsewhere} hands it to the system's player */
    VideoControls(MediaPlayer p, Stage stage, MediaView view, StackPane screen, Runnable onSave, Runnable openElsewhere) {
        super(6);
        this.p = p;
        this.stage = stage;
        this.view = view;
        this.screen = screen;
        getStyleClass().add("video-controls");

        toggle = Icons.iconButton(Icons.PAUSE, I18n.t("video.pause"), this::togglePlay);
        Button back = Icons.iconButton(Icons.REWIND, I18n.t("video.back10"), () -> seekBy(-SKIP));
        Button forward = Icons.iconButton(Icons.FAST_FORWARD, I18n.t("video.forward10"), () -> seekBy(SKIP));
        mute = Icons.iconButton(Icons.VOLUME, I18n.t("video.mute"), this::toggleMute);
        volume.setAccessibleText(I18n.t("video.volume"));
        volume.setPrefWidth(90);
        volume.setMinWidth(60);
        p.volumeProperty().bind(volume.valueProperty());
        volume.valueProperty().addListener((o, a, v) -> lastVolume = v.doubleValue());
        repeat = Icons.iconButton(Icons.REPEAT, I18n.t("video.repeat"), this::toggleRepeat);
        Button frame = Icons.iconButton(Icons.CAMERA, I18n.t("video.saveFrame"), this::saveFrame);
        Button elsewhere = Icons.iconButton(Icons.EXTERNAL, I18n.t("video.openElsewhere"), openElsewhere);
        onTop = Icons.iconButton(Icons.PIN, I18n.t("video.onTop"), this::toggleOnTop);
        full = Icons.iconButton(Icons.FULLSCREEN, I18n.t("video.fullScreen"), this::toggleFullScreen);
        speedMenu();

        Label time = new Label(VideoViewer.clock(0));
        time.getStyleClass().add("video-time");
        time.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setMargin(time, new javafx.geometry.Insets(0, 0, 0, 8));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(4, toggle, back, forward, mute, volume, time, grow, speed, repeat, frame);
        if (onSave != null) buttons.getChildren().add(Icons.iconButton(Icons.DOWNLOAD, I18n.t("video.save"), onSave));
        buttons.getChildren().addAll(elsewhere, onTop, full);
        buttons.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(position(time), buttons);

        replay = replayButton();
        p.setRate(lastRate);
        p.setOnRepeat(this::reapplyRate);
        p.statusProperty().addListener((o, a, st) -> showPlaying(st == MediaPlayer.Status.PLAYING && !ended));
        p.setOnEndOfMedia(() -> {
            if (p.getCycleCount() == MediaPlayer.INDEFINITE) return;
            ended = true;
            p.pause();
            showPlaying(false);
            if (!screen.getChildren().contains(replay)) screen.getChildren().add(replay);
        });
        stage.fullScreenProperty().addListener((o, a, on) -> fullScreenChanged(on));
        stage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH); // Esc is ours: VideoViewer
        stage.setFullScreenExitHint(I18n.t("video.fullScreenHint"));
        idle.setOnFinished(e -> hideWhileWatching());
        screen.setOnMouseMoved(e -> wake());
        setOnMouseMoved(e -> wake());
        view.setOnMouseClicked(e -> {
            togglePlay(); // a double click also toggles twice: it keeps playing, as YouTube does
            if (e.getClickCount() == 2) toggleFullScreen();
        });
    }

    /** The bar above the buttons: drag or click to move, the arrows move 5 s when it has the focus. */
    private Node position(Label time) {
        Slider position = new Slider(0, 1, 0);
        position.setAccessibleText(I18n.t("video.position"));
        position.getStyleClass().add("video-position");
        HBox.setHgrow(position, Priority.ALWAYS);
        boolean[] fromPlayer = {false};
        p.currentTimeProperty().addListener((o, a, now) -> {
            double total = p.getTotalDuration().toSeconds();
            time.setText(VideoViewer.clock(now.toSeconds()) + " / " + VideoViewer.clock(total));
            if (position.isValueChanging() || !(total > 0)) return;
            fromPlayer[0] = true;
            position.setValue(now.toSeconds() / total);
            fromPlayer[0] = false;
        });
        // A click on the track changes the value without "changing": seek on every value the player didn't set.
        position.valueProperty().addListener((o, a, v) -> {
            if (!fromPlayer[0] && !position.isValueChanging()) seekTo(v.doubleValue());
        });
        position.valueChangingProperty().addListener((o, a, moving) -> {
            if (!moving) seekTo(position.getValue());
        });
        return position;
    }

    private void speedMenu() {
        speed.getStyleClass().add("video-speed");
        for (double r : RATES) {
            RadioMenuItem item = new RadioMenuItem(rateLabel(r));
            item.setToggleGroup(rates);
            item.setSelected(r == lastRate);
            item.setOnAction(e -> setRate(r));
            speed.getItems().add(item);
        }
        showRate(lastRate);
    }

    private Node replayButton() {
        Button b = new Button(I18n.t("video.replay"), Icons.icon(Icons.REPLAY, 28));
        b.getStyleClass().add("video-replay");
        b.setOnAction(e -> replay());
        StackPane.setAlignment(b, Pos.CENTER);
        return b;
    }

    // ------------------------------------------------------------------ actions

    void togglePlay() {
        if (ended) {
            replay();
        } else if (p.getStatus() == MediaPlayer.Status.PLAYING) {
            p.pause();
        } else {
            p.play();
        }
    }

    void replay() {
        ended = false;
        screen.getChildren().remove(replay);
        p.seek(Duration.ZERO);
        p.play();
        reapplyRate();
    }

    /**
     * Starting over, the native player goes back to 1x while {@code getRate()} keeps saying the chosen speed, so
     * setting it to the same value would do nothing: go through 1 to make it take.
     */
    private void reapplyRate() {
        double r = p.getRate();
        if (r == 1) return;
        p.setRate(1);
        p.setRate(r);
    }

    void seekBy(double seconds) {
        seekToSeconds(p.getCurrentTime().toSeconds() + seconds);
    }

    /** {@code fraction} of the video, 0 to 1. */
    void seekTo(double fraction) {
        seekToSeconds(fraction * p.getTotalDuration().toSeconds());
    }

    private void seekToSeconds(double s) {
        double total = p.getTotalDuration().toSeconds();
        double to = clamp(s, total);
        if (ended && to < total) {
            ended = false;
            screen.getChildren().remove(replay);
            showPlaying(p.getStatus() == MediaPlayer.Status.PLAYING);
        }
        p.seek(Duration.seconds(to));
    }

    /** One frame (about 1/30 s) back or forward, paused: to stop at the exact moment. */
    void frameStep(int dir) {
        p.pause();
        seekBy(dir * FRAME);
    }

    void volumeBy(double delta) {
        volume.setValue(Math.max(0, Math.min(1, volume.getValue() + delta)));
        if (p.isMute() && delta > 0) toggleMute();
    }

    void toggleMute() {
        p.setMute(!p.isMute());
        mute.setGraphic(Icons.icon(p.isMute() ? Icons.VOLUME_OFF : Icons.VOLUME, 18));
        mute.setAccessibleText(I18n.t(p.isMute() ? "video.unmute" : "video.mute"));
        mute.getTooltip().setText(mute.getAccessibleText());
    }

    void rateStep(int dir) {
        setRate(nextRate(p.getRate(), dir));
    }

    private void setRate(double r) {
        lastRate = r;
        p.setRate(r);
        showRate(r);
    }

    private void showRate(double r) {
        speed.setText(rateLabel(r));
        speed.setAccessibleText(I18n.t("video.speed", rateLabel(r)));
        rates.getToggles().forEach(t -> t.setSelected(((RadioMenuItem) t).getText().equals(rateLabel(r))));
    }

    private void toggleRepeat() {
        boolean on = p.getCycleCount() != MediaPlayer.INDEFINITE;
        p.setCycleCount(on ? MediaPlayer.INDEFINITE : 1);
        if (on && ended) replay();
        Nodes.style(repeat, "active", on);
        repeat.setAccessibleText(I18n.t(on ? "video.repeatOff" : "video.repeat"));
        repeat.getTooltip().setText(repeat.getAccessibleText());
    }

    void toggleFullScreen() {
        stage.setFullScreen(!stage.isFullScreen());
    }

    private void toggleOnTop() {
        stage.setAlwaysOnTop(!stage.isAlwaysOnTop());
        Nodes.style(onTop, "active", stage.isAlwaysOnTop());
        onTop.setAccessibleText(I18n.t(stage.isAlwaysOnTop() ? "video.onTopOff" : "video.onTop"));
        onTop.getTooltip().setText(onTop.getAccessibleText());
    }

    /** The picture on screen, as big as the video is, to a PNG the person chooses. */
    private void saveFrame() {
        boolean playing = p.getStatus() == MediaPlayer.Status.PLAYING;
        p.pause();
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        double scale = p.getMedia().getWidth() > 0 && view.getBoundsInLocal().getWidth() > 0
                ? p.getMedia().getWidth() / view.getBoundsInLocal().getWidth() : 1;
        params.setTransform(javafx.scene.transform.Transform.scale(scale, scale));
        javafx.scene.image.WritableImage img = view.snapshot(params, null);
        FileChooser fc = new FileChooser();
        fc.setTitle(I18n.t("video.saveFrame"));
        fc.setInitialFileName(frameName(stage.getTitle(), p.getCurrentTime().toSeconds()));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File f = fc.showSaveDialog(stage);
        if (f != null) {
            try {
                javax.imageio.ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", f);
            } catch (java.io.IOException e) {
                System.err.println("Could not save the frame: " + e);
            }
        }
        if (playing) p.play();
    }

    // ------------------------------------------------------------------ what shows

    private void showPlaying(boolean playing) {
        String icon = ended ? Icons.REPLAY : playing ? Icons.PAUSE : Icons.PLAY;
        toggle.setGraphic(Icons.icon(icon, 18));
        toggle.setAccessibleText(I18n.t(ended ? "video.replay" : playing ? "video.pause" : "video.play"));
        toggle.getTooltip().setText(toggle.getAccessibleText());
        if (playing) wake();
        else showControls(true);
    }

    private void fullScreenChanged(boolean on) {
        full.setGraphic(Icons.icon(on ? Icons.FULLSCREEN_EXIT : Icons.FULLSCREEN, 18));
        full.setAccessibleText(I18n.t(on ? "video.exitFullScreen" : "video.fullScreen"));
        full.getTooltip().setText(full.getAccessibleText());
        wake();
    }

    /** The mouse moved or a key was pressed: the controls show, and in full screen hide again after a while. */
    void wake() {
        showControls(true);
        idle.playFromStart();
    }

    private void hideWhileWatching() {
        boolean watching = stage.isFullScreen() && p.getStatus() == MediaPlayer.Status.PLAYING && !isHover();
        if (watching) showControls(false);
    }

    private void showControls(boolean on) {
        setOpacity(on ? 1 : 0);
        setMouseTransparent(!on);
        screen.setCursor(on ? Cursor.DEFAULT : Cursor.NONE);
    }

    /** The player's keys; true when it was one. Esc (full screen, then close) is the window's. */
    boolean key(KeyEvent e) {
        if (e.isShortcutDown()) return false;
        wake();
        String text = e.getText() == null ? "" : e.getText();
        KeyCode c = e.getCode();
        // A focused control keeps its own keys: Space and Enter press a button, the arrows move a slider.
        if ((c == KeyCode.SPACE || c == KeyCode.ENTER) && e.getTarget() instanceof javafx.scene.control.ButtonBase) return false;
        if (c.isArrowKey() && e.getTarget() == volume) return false;
        if (c == KeyCode.SPACE || c == KeyCode.K) togglePlay();
        else if (c == KeyCode.LEFT || c == KeyCode.RIGHT) seekBy(c == KeyCode.LEFT ? -ARROW_SKIP : ARROW_SKIP);
        else if (c == KeyCode.J || c == KeyCode.L) seekBy(c == KeyCode.J ? -SKIP : SKIP);
        else if (c == KeyCode.UP || c == KeyCode.DOWN) volumeBy(c == KeyCode.UP ? 0.1 : -0.1);
        else if (c == KeyCode.M) toggleMute();
        else if (c == KeyCode.F) toggleFullScreen();
        else if (text.equals(">") || text.equals("<")) rateStep(text.equals(">") ? 1 : -1);
        else if (text.equals(".") || text.equals(",")) frameStep(text.equals(".") ? 1 : -1);
        else if (c == KeyCode.HOME) seekTo(0);
        else if (c == KeyCode.END) seekTo(1);
        else if (c.isDigitKey()) seekTo((c.getCode() - KeyCode.DIGIT0.getCode()) / 10.0);
        else return false;
        return true;
    }

    // ------------------------------------------------------------------ pure, tested

    /** The next speed up ({@code dir} 1) or down (-1) from {@code current}; stays at the ends. */
    static double nextRate(double current, int dir) {
        int at = 0;
        for (int i = 0; i < RATES.length; i++) if (Math.abs(RATES[i] - current) < Math.abs(RATES[at] - current)) at = i;
        return RATES[Math.max(0, Math.min(RATES.length - 1, at + dir))];
    }

    /** "1×", "1.5×", "0.25×". */
    static String rateLabel(double r) {
        String s = String.format(Locale.ROOT, "%.2f", r).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s + "×";
    }

    /** Within the video; before its length is known, anywhere from 0. */
    static double clamp(double seconds, double total) {
        double s = Math.max(0, seconds);
        return total > 0 ? Math.min(s, total) : s;
    }

    /** "clip.mp4" at 65.4 s → "clip-1m05s.png". */
    static String frameName(String title, double seconds) {
        String base = title == null || title.isBlank() ? "video" : title.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
        long s = (long) Math.max(0, seconds);
        return base.replaceAll("[\\\\/:*?\"<>|]", "_") + "-" + (s / 60) + "m" + String.format("%02d", s % 60) + "s.png";
    }
}

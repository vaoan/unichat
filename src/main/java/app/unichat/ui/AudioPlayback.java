package app.unichat.ui;

import app.unichat.support.Threads;
import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.service.MediaSource;
import app.unichat.stt.PlayableAudio;
import javafx.application.Platform;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The one audio playing, whichever chat is on screen: starting another stops it, and every control (the message's and
 * the bar on top of the window) shows and drives this same player. FX thread only.
 */
final class AudioPlayback {
    enum State { LOADING, PLAYING, PAUSED }

    /** Decoding a voice note: not on the FX thread, one at a time. */
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(Threads.daemon("audio-decode"));

    private final MediaSource media;
    private final Consumer<String> toast;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Message current;
    private Chat chat;
    private State state;
    private MediaPlayer player;
    private double volume = 1;
    /** Changes with every start and stop, so a download that finishes late can tell nobody wants it any more. */
    private long generation;

    AudioPlayback(MediaSource media, Consumer<String> toast) {
        this.media = media;
        this.toast = toast;
    }

    Message current() { return current; }
    Chat chat() { return chat; }
    State state() { return state; }
    boolean isCurrent(Message m) { return current != null && m != null && current.id().equals(m.id()); }
    int listenerCount() { return listeners.size(); }
    void addListener(Runnable l) { listeners.add(l); }
    void removeListener(Runnable l) { listeners.remove(l); }

    /** Seconds played of the current audio. */
    double position() { return player == null ? 0 : player.getCurrentTime().toSeconds(); }

    /** Seconds it lasts; before it loads, what the bridge reported. */
    double duration() {
        if (player != null && player.getTotalDuration().toSeconds() > 0) return player.getTotalDuration().toSeconds();
        return current == null ? 0 : current.durationMs() / 1000.0;
    }

    /** Plays {@code m}, or pauses or resumes it if it's the one already here. */
    void toggle(Message m, Chat inChat) {
        if (isCurrent(m)) {
            togglePlay();
            return;
        }
        stop();
        current = m;
        chat = inChat;
        state = State.LOADING;
        long mine = ++generation;
        fire();
        media.media(m.mediaUrl(), 0, 0)
                .thenApplyAsync(AudioPlayback::playable, DECODER)
                .whenComplete((file, err) -> Platform.runLater(() -> {
                    if (mine != generation) return;
                    if (err != null) failed();
                    else open(file);
                }));
    }

    void togglePlay() {
        if (player == null) return;
        if (state == State.PLAYING) player.pause();
        else player.play();
    }

    void pause() {
        if (player != null && state == State.PLAYING) player.pause();
    }

    /** {@code fraction} of the way through the audio. */
    void seek(double fraction) {
        if (player != null) player.seek(player.getTotalDuration().multiply(fraction));
    }

    void stop() {
        generation++;
        if (player != null) player.dispose();
        player = null;
        boolean had = current != null;
        current = null;
        chat = null;
        state = null;
        if (had) fire();
    }

    /** So tests don't sound through the speakers. */
    void setVolume(double v) {
        volume = v;
        if (player != null) player.setVolume(v);
    }

    private static Path playable(Path file) {
        try {
            return PlayableAudio.of(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void open(Path file) {
        long mine = generation;
        MediaPlayer p = MediaPlayers.open(file, () -> {
            if (mine == generation) failed();
        });
        if (p == null) return;
        player = p;
        p.setVolume(volume);
        p.setOnEndOfMedia(this::stop);
        p.currentTimeProperty().addListener((o, a, now) -> {
            fire();
            // A seek that lands on the very end may never get its end-of-media call.
            Duration total = p.getTotalDuration();
            if (total != null && total.toMillis() > 0 && now.greaterThanOrEqualTo(total)) Platform.runLater(() -> {
                if (mine == generation) stop();
            });
        });
        p.statusProperty().addListener((o, a, st) -> {
            if (st == MediaPlayer.Status.PLAYING) state = State.PLAYING;
            else if (st == MediaPlayer.Status.PAUSED) state = State.PAUSED;
            fire();
        });
        p.setOnReady(() -> {
            p.seek(Duration.ZERO);
            p.play();
        });
    }

    private void failed() {
        stop();
        toast.accept("No se pudo reproducir el audio");
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }
}

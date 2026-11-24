package app.unichat.ui.media;

import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.Theme;
import app.unichat.ui.desktop.Notifier;
import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.MediaSource;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import app.unichat.Present;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real playback of a real voice note, silently. {@code ./mvnw test -Dtest=AudioUiTest -Dunichat.uiTests=true}. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AudioUiTest {
    @TempDir Path dir;
    private final List<String> toasts = new ArrayList<>();
    private AudioPlayback playback;

    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get();
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void player() throws Exception {
        Path note = dir.resolve("nota.ogg");
        try (InputStream in = AudioUiTest.class.getResourceAsStream("/app/unichat/demo/voice-note.ogg")) {
            Files.copy(in, note);
        }
        MediaSource source = (url, w, h) -> "mxc://s/roto".equals(url)
                ? CompletableFuture.failedFuture(new java.io.IOException("no"))
                : CompletableFuture.completedFuture(note);
        playback = fx(() -> {
            AudioPlayback p = new AudioPlayback(source, toasts::add);
            p.setVolume(0);
            return p;
        });
    }

    private static <T extends @Nullable Object, E extends Exception> T fx(FxWait.FxCall<T, E> c) throws E, InterruptedException {
        return FxWait.fx(c);
    }

    private static void await(Callable<Boolean> c, String what) {
        FxWait.until(what, c);
    }

    private static Message audio(String id, String url) {
        Message m = new Message(id, "!c", "@a:s", "Ana", false, Instant.EPOCH);
        m.setType(MessageType.AUDIO);
        m.setMediaUrl(url);
        return m;
    }

    /** Playing, or failed to: a failure ends the wait at once and says so (it waited to the limit on a machine with no sound). */
    private void awaitPlaying(Message m) {
        await(() -> !toasts.isEmpty() || (playback.isCurrent(m) && playback.state() == AudioPlayback.State.PLAYING), m.id() + " plays");
        assertEquals(List.of(), toasts, m.id() + " didn't play");
    }

    @Test
    void onlyOnePlaysAtATime() throws Exception {
        Message a = audio("$a", "mxc://s/a");
        Message b = audio("$b", "mxc://s/b");
        FxWait.onFx(() -> playback.toggle(a, null));
        awaitPlaying(a);
        FxWait.onFx(() -> playback.toggle(b, null));
        awaitPlaying(b);
        assertFalse(fx(() -> playback.isCurrent(a)));
        FxWait.onFx(playback::stop);
    }

    @Test
    void theSameOnePausesAndResumes() throws Exception {
        Message a = audio("$a", "mxc://s/a");
        FxWait.onFx(() -> playback.toggle(a, null));
        awaitPlaying(a);
        FxWait.onFx(() -> playback.toggle(a, null));
        await(() -> playback.state() == AudioPlayback.State.PAUSED, "paused");
        FxWait.onFx(() -> playback.toggle(a, null));
        awaitPlaying(a);
        FxWait.onFx(playback::stop);
    }

    @Test
    void atTheEndItsGone() throws Exception {
        Message a = audio("$a", "mxc://s/a");
        FxWait.onFx(() -> playback.toggle(a, null));
        awaitPlaying(a);
        FxWait.onFx(() -> playback.seek(0.97));
        await(() -> playback.current() == null, "finished");
        assertNull(fx(playback::state));
    }

    @Test
    void whatCantBeHadSaysSo() throws Exception {
        Message a = audio("$a", "mxc://s/roto");
        FxWait.onFx(() -> playback.toggle(a, null));
        await(() -> playback.current() == null, "gave up");
        await(() -> toasts.contains("No se pudo reproducir el audio"), "said so");
    }

    @Test
    void theBarFollowsItAndControlsItFromAnotherChat() throws Exception {
        Chat chat = new Chat("!c");
        chat.setTitle("Familia");
        List<Chat> opened = new ArrayList<>();
        StackPane[] holder = new StackPane[1];
        AudioBar bar = fx(() -> new AudioBar(playback, opened::add));
        Stage stage = fx(() -> {
            holder[0] = new StackPane();
            Stage s = new Stage();
            Scene scene = new Scene(new VBox(bar, holder[0]), 700, 300);
            Theme.register(scene);
            s.setScene(scene);
            s.show();
            return s;
        });
        assertFalse(fx(bar::isVisible), "nothing to control yet");
        Message a = audio("$a", "mxc://s/a");
        FxWait.onFx(() -> playback.toggle(a, chat));
        awaitPlaying(a);
        assertTrue(fx(bar::isVisible));
        FxWait.onFx(() -> bar.lookupAll(".hyperlink").forEach(n -> ((javafx.scene.control.Hyperlink) n).fire()));
        assertEquals(List.of(chat), opened);

        FxWait.onFx(() -> ((Button) bar.lookup(".audio-toggle")).fire());
        await(() -> playback.state() == AudioPlayback.State.PAUSED, "the bar pauses it");
        FxWait.onFx(() -> ((Button) bar.lookup(".audio-toggle")).fire());
        awaitPlaying(a);
        FxWait.onFx(() -> {
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png",
                    Path.of(System.getProperty("java.io.tmpdir"), "unichat-audio-bar.png").toFile());
        });
        FxWait.onFx(() -> bar.getChildren().stream().filter(n -> n instanceof Button).map(n -> (Button) n).forEach(Button::fire));
        assertNull(fx(playback::current));
        assertFalse(fx(bar::isVisible));
        FxWait.onFx(stage::close);
    }

    @Test
    void itKeepsPlayingWhenYouChangeChatAndTheBarStillControlsIt() throws Exception {
        app.unichat.service.MockChatService svc = new app.unichat.service.MockChatService();
        Stage[] stage = new Stage[1];
        MainView view = fx(() -> {
            stage[0] = new Stage();
            MainView v = new MainView(svc, stage[0], new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            v.audio().setVolume(0);
            Scene sc = new Scene(v, 1100, 760);
            Theme.register(sc);
            stage[0].setScene(sc);
            stage[0].show();
            v.openChatId("!family:demo");
            return v;
        });
        AudioPlayback audio = view.audio();
        await(() -> view.lookup(".timeline .audio-toggle") != null, "the voice note shows its player");
        FxWait.settled(); // the timeline done drawing, so the button pressed is the one that stays
        FxWait.onFx(() -> ((Button) view.lookup(".timeline .audio-toggle")).fire());
        await(() -> audio.state() == AudioPlayback.State.PLAYING, "plays");
        assertEquals("!family:demo", fx(() -> Present.of(audio.chat()).id()));
        FxWait.onFx(() -> {
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(view.getScene().snapshot(null), null), "png",
                    Path.of(System.getProperty("java.io.tmpdir"), "unichat-audio-window.png").toFile());
        });

        // Paused first: the demo note lasts 2 s, so "still playing" after changing chats depended on how loaded the
        // machine was (it failed in a full run). Paused, it must still be there, and the bar must start it again.
        FxWait.onFx(audio::pause);
        await(() -> audio.state() == AudioPlayback.State.PAUSED, "paused");
        FxWait.onFx(() -> view.openChatId("!andres:demo"));
        FxWait.settled(); // the other chat fully shown
        assertEquals(AudioPlayback.State.PAUSED, fx(audio::state), "changing chats doesn't stop it");
        assertEquals("!family:demo", fx(() -> Present.of(audio.chat()).id()));
        Node bar = fx(() -> view.lookup(".audio-bar"));
        assertTrue(fx(bar::isVisible));
        FxWait.onFx(() -> ((Button) bar.lookup(".audio-toggle")).fire());
        await(() -> audio.state() == AudioPlayback.State.PLAYING, "the bar controls it from the other chat");
        FxWait.onFx(() -> {
            audio.stop();
            stage[0].close();
            svc.stop();
        });
    }

    @Test
    void aRowThatLeavesStopsListening() throws Exception {
        Message a = audio("$a", "mxc://s/a");
        StackPane root = fx(StackPane::new);
        Stage stage = fx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(root, 300, 100));
            s.show();
            return s;
        });
        int before = fx(playback::listenerCount);
        Node controls = fx(() -> {
            AudioControls c = new AudioControls(playback, () -> a, () -> playback.toggle(a, null));
            root.getChildren().add(c);
            return c;
        });
        assertEquals(before + 1, fx(playback::listenerCount));
        fx(() -> root.getChildren().remove(controls));
        assertEquals(before, fx(playback::listenerCount));
        FxWait.onFx(stage::close);
    }
}

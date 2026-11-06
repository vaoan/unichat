package app.unichat.ui;

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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real playback of a real voice note, silently. {@code ./mvnw test -Dtest=AudioUiTest -Dunichat.uiTests=true}. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
@org.junit.jupiter.api.condition.DisabledIfSystemProperty(named = "unichat.headless", matches = "true", disabledReason = "JavaFX stops under Monocle; needs a display")
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
        up.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void player() throws Exception {
        Path note = dir.resolve("nota.ogg");
        try (InputStream in = AudioUiTest.class.getResourceAsStream("/app/unichat/demo/nota-de-voz.ogg")) {
            Files.copy(in, note);
        }
        MediaSource source = (url, w, h) -> url.equals("mxc://s/roto")
                ? CompletableFuture.failedFuture(new java.io.IOException("no"))
                : CompletableFuture.completedFuture(note);
        playback = fx(() -> {
            AudioPlayback p = new AudioPlayback(source, toasts::add);
            p.setVolume(0);
            return p;
        });
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c);
        Platform.runLater(t);
        return t.get(20, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> c, String what) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (fx(c)) return;
            Thread.sleep(50);
        }
        assertTrue(fx(c), what);
    }

    private static Message audio(String id, String url) {
        Message m = new Message(id, "!c", "@a:s", "Ana", false, Instant.EPOCH);
        m.setType(MessageType.AUDIO);
        m.setMediaUrl(url);
        return m;
    }

    private void awaitPlaying(Message m) throws Exception {
        await(() -> playback.isCurrent(m) && playback.state() == AudioPlayback.State.PLAYING, m.id() + " plays");
    }

    @Test
    void onlyOnePlaysAtATime() throws Exception {
        Message a = audio("$a", "mxc://s/a"), b = audio("$b", "mxc://s/b");
        fx(() -> { playback.toggle(a, null); return null; });
        awaitPlaying(a);
        fx(() -> { playback.toggle(b, null); return null; });
        awaitPlaying(b);
        assertFalse(fx(() -> playback.isCurrent(a)));
        fx(() -> { playback.stop(); return null; });
    }

    @Test
    void theSameOnePausesAndResumes() throws Exception {
        Message a = audio("$a", "mxc://s/a");
        fx(() -> { playback.toggle(a, null); return null; });
        awaitPlaying(a);
        fx(() -> { playback.toggle(a, null); return null; });
        await(() -> playback.state() == AudioPlayback.State.PAUSED, "paused");
        fx(() -> { playback.toggle(a, null); return null; });
        awaitPlaying(a);
        fx(() -> { playback.stop(); return null; });
    }

    @Test
    void atTheEndItsGone() throws Exception {
        Message a = audio("$a", "mxc://s/a");
        fx(() -> { playback.toggle(a, null); return null; });
        awaitPlaying(a);
        fx(() -> { playback.seek(0.97); return null; });
        await(() -> playback.current() == null, "finished");
        assertNull(fx(playback::state));
    }

    @Test
    void whatCantBeHadSaysSo() throws Exception {
        Message a = audio("$a", "mxc://s/roto");
        fx(() -> { playback.toggle(a, null); return null; });
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
        fx(() -> { playback.toggle(a, chat); return null; });
        awaitPlaying(a);
        assertTrue(fx(bar::isVisible));
        fx(() -> { bar.lookupAll(".hyperlink").forEach(n -> ((javafx.scene.control.Hyperlink) n).fire()); return null; });
        assertEquals(List.of(chat), opened);

        fx(() -> { ((Button) bar.lookup(".audio-toggle")).fire(); return null; });
        await(() -> playback.state() == AudioPlayback.State.PAUSED, "the bar pauses it");
        fx(() -> { ((Button) bar.lookup(".audio-toggle")).fire(); return null; });
        awaitPlaying(a);
        fx(() -> {
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png",
                    Path.of(System.getProperty("java.io.tmpdir"), "unichat-audio-bar.png").toFile());
            return null;
        });
        fx(() -> { bar.getChildren().stream().filter(n -> n instanceof Button).map(n -> (Button) n).forEach(Button::fire); return null; });
        assertNull(fx(playback::current));
        assertFalse(fx(bar::isVisible));
        fx(() -> { stage.close(); return null; });
    }

    @Test
    void itKeepsPlayingWhenYouChangeChatAndTheBarStillControlsIt() throws Exception {
        app.unichat.service.MockChatService svc = new app.unichat.service.MockChatService();
        Stage[] stage = new Stage[1];
        MainView view = fx(() -> {
            stage[0] = new Stage();
            MainView v = new MainView(svc, stage[0], new Notifier(() -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            v.audio().setVolume(0);
            Scene sc = new Scene(v, 1100, 760);
            Theme.register(sc);
            stage[0].setScene(sc);
            stage[0].show();
            v.openChatId("!familia:demo");
            return v;
        });
        AudioPlayback audio = view.audio();
        await(() -> view.lookup(".timeline .audio-toggle") != null, "the voice note shows its player");
        Thread.sleep(300);
        fx(() -> { ((Button) view.lookup(".timeline .audio-toggle")).fire(); return null; });
        await(() -> audio.state() == AudioPlayback.State.PLAYING, "plays");
        assertEquals("!familia:demo", fx(() -> audio.chat().id()));
        fx(() -> {
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(view.getScene().snapshot(null), null), "png",
                    Path.of(System.getProperty("java.io.tmpdir"), "unichat-audio-window.png").toFile());
            return null;
        });

        fx(() -> { view.openChatId("!andres:demo"); return null; });
        Thread.sleep(300);
        assertEquals(AudioPlayback.State.PLAYING, fx(audio::state), "changing chats doesn't stop it");
        Node bar = fx(() -> view.lookup(".audio-bar"));
        assertTrue(fx(bar::isVisible));
        fx(() -> { ((Button) bar.lookup(".audio-toggle")).fire(); return null; });
        await(() -> audio.state() == AudioPlayback.State.PAUSED, "the bar controls it from the other chat");
        fx(() -> { audio.stop(); stage[0].close(); svc.stop(); return null; });
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
        fx(() -> { stage.close(); return null; });
    }
}

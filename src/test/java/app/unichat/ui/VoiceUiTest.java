package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.MockChatService;
import app.unichat.voice.PcmSource;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The composer's recording flow, with samples in place of the microphone. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class VoiceUiTest {
    private static final String CHAT = "!familia:demo";
    private MockChatService svc;
    private Composer composer;
    private Stage stage;
    private final List<String> toasts = new ArrayList<>();
    private volatile short amplitude = 8_000;

    private final class Mic implements PcmSource {
        private volatile boolean closed;

        @Override public int rate() { return 48_000; }

        @Override
        public int read(short[] into) throws java.io.IOException {
            if (closed) return -1;
            try {
                Thread.sleep(into.length * 1000L / 48_000);
            } catch (InterruptedException e) {
                throw new java.io.IOException(e);
            }
            for (int i = 0; i < into.length; i++) into[i] = i % 2 == 0 ? amplitude : (short) -amplitude;
            return into.length;
        }

        @Override public void close() { closed = true; }
    }

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
    void open() throws Exception {
        app.unichat.voice.VoiceEnhancer.setEnabled(false); // its own test covers it; nothing to download here
        svc = new MockChatService();
        fx(() -> {
            composer = new Composer(svc, toasts::add);
            composer.setMicrophone(Mic::new);
            stage = new Stage();
            Scene scene = new Scene(composer, 700, 120);
            Theme.register(scene);
            stage.setScene(scene);
            stage.show();
            composer.setChat(CHAT);
            composer.refreshVoice();
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> { stage.close(); return null; });
        svc.stop();
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c);
        Platform.runLater(t);
        return t.get(20, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> c, String what) throws Exception {
        for (int i = 0; i < 120; i++) {
            if (fx(c)) return;
            Thread.sleep(50);
        }
        assertTrue(fx(c), what);
    }

    private Node mic() { return composer.lookup(".mic-button"); }

    private void startRecording() throws Exception {
        fx(() -> { ((Button) mic()).fire(); return null; });
        await(() -> composer.lookup(".voice-bar") != null, "the recording bar shows");
    }

    private List<Message> voiceNotes() {
        return svc.messages(CHAT).stream().filter(m -> m.type() == MessageType.AUDIO && m.fileName() != null && m.fileName().startsWith("voice-")).toList();
    }

    @Test
    void theMicIsThereUntilSomethingIsWritten() throws Exception {
        assertTrue(fx(() -> mic().isVisible()));
        assertFalse(fx(() -> composer.lookup(".send-button").isVisible()));
        fx(() -> { composer.insert("hola"); return null; });
        assertFalse(fx(() -> mic().isVisible()));
        assertTrue(fx(() -> composer.lookup(".send-button").isVisible()));
    }

    @Test
    void recordingAndSendingLeavesAVoiceNoteWithItsLength() throws Exception {
        startRecording();
        Thread.sleep(1200);
        fx(() -> {
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png",
                    java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "unichat-voice-bar.png").toFile());
            ((Button) composer.lookup(".voice-bar .send-button")).fire();
            return null;
        });
        await(() -> voiceNotes().size() == 1, "sent");
        assertTrue(voiceNotes().get(0).durationMs() >= 800, "lasts " + voiceNotes().get(0).durationMs());
        assertTrue(fx(() -> composer.lookup(".voice-bar") == null), "back to writing");
    }

    @Test
    void aReplyStaysAReply() throws Exception {
        Message first = svc.messages(CHAT).get(0);
        fx(() -> { composer.reply(first); return null; });
        startRecording();
        Thread.sleep(900);
        fx(() -> { ((Button) composer.lookup(".voice-bar .send-button")).fire(); return null; });
        await(() -> voiceNotes().size() == 1, "sent");
        assertEquals(first.id(), voiceNotes().get(0).replyToId());
    }

    @Test
    void discardingSendsNothing() throws Exception {
        startRecording();
        Thread.sleep(300);
        fx(() -> { composer.lookup(".voice-bar").lookupAll(".icon-button").stream().findFirst().map(n -> (Button) n).orElseThrow().fire(); return null; });
        assertTrue(fx(() -> composer.lookup(".voice-bar") == null));
        Thread.sleep(300);
        assertTrue(voiceNotes().isEmpty());
    }

    @Test
    void tooShortIsNotSent() throws Exception {
        startRecording();
        fx(() -> { ((Button) composer.lookup(".voice-bar .send-button")).fire(); return null; });
        await(() -> toasts.contains("La nota de voz es demasiado corta"), "says so");
        assertTrue(voiceNotes().isEmpty());
    }

    @Test
    void changingChatDiscardsIt() throws Exception {
        startRecording();
        fx(() -> { composer.setChat("!andres:demo"); return null; });
        assertTrue(fx(() -> composer.lookup(".voice-bar") == null));
        assertTrue(toasts.contains("Grabación descartada al cambiar de chat"));
    }

    @Test
    void aMicThatGivesPureSilenceIsReportedInsteadOfSendingNothing() throws Exception {
        amplitude = 0;
        startRecording();
        await(() -> toasts.stream().anyMatch(t -> t.startsWith("El micrófono no da sonido")), "says so");
        assertTrue(fx(() -> composer.lookup(".voice-bar") == null));
        assertTrue(voiceNotes().isEmpty());
    }

    @Test
    void noMicSaysSo() throws Exception {
        fx(() -> {
            composer.setMicrophone(() -> { throw new java.io.IOException("No se encontró un micrófono disponible"); });
            ((Button) mic()).fire();
            return null;
        });
        await(() -> toasts.contains("No se encontró un micrófono disponible"), "says so");
        assertFalse(fx(() -> ((Button) mic()).isDisabled()));
    }
}

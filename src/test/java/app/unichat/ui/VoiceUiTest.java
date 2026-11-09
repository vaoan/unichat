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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

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
    /** Reads the mic may answer; it hands over only what the test gave it ({@link #speak}), then waits for more. */
    private final Semaphore reads = new Semaphore(0);
    private volatile CountDownLatch heard = new CountDownLatch(0);

    private final class Mic implements PcmSource {
        private volatile boolean closed;

        @Override public int rate() { return RATE; }

        @Override
        public int read(short[] into) throws java.io.IOException {
            try {
                reads.acquire();
            } catch (InterruptedException e) {
                throw new java.io.IOException(e);
            }
            if (closed) return -1;
            for (int i = 0; i < into.length; i++) into[i] = i % 2 == 0 ? amplitude : (short) -amplitude;
            heard.countDown();
            return into.length;
        }

        @Override
        public void close() {
            closed = true;
            reads.release(); // wakes a read waiting for more
        }
    }

    private static final int RATE = 48_000;
    /** What the recorder asks for in each read: 1/25 s. */
    private static final long READ_MS = 40;

    /** Hands the recording {@code ms} of sound, all at once, and waits until the recorder has it. */
    private void speak(long ms) throws InterruptedException {
        offer(ms);
        heard.await();
    }

    /** Lets the mic hand over up to {@code ms} of sound, without waiting for the recorder to take it. */
    private void offer(long ms) {
        int n = (int) (ms / READ_MS);
        heard = new CountDownLatch(n);
        reads.release(n);
    }

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
        return FxWait.fx(c);
    }

    private static void await(Callable<Boolean> c, String what) throws Exception {
        FxWait.until(what, c);
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
        speak(1200);
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
        speak(1500); // well over the shortest note
        fx(() -> { ((Button) composer.lookup(".voice-bar .send-button")).fire(); return null; });
        await(() -> voiceNotes().size() == 1, "sent");
        assertEquals(first.id(), voiceNotes().get(0).replyToId());
    }

    @Test
    void discardingSendsNothing() throws Exception {
        java.util.Set<Path> before = recordings();
        startRecording();
        java.util.Set<Path> recording = recordings();
        recording.removeAll(before);
        assertEquals(1, recording.size(), "the recording's file");
        Path file = recording.iterator().next();
        speak(300);
        fx(() -> { composer.lookup(".voice-bar").lookupAll(".icon-button").stream().findFirst().map(n -> (Button) n).orElseThrow().fire(); return null; });
        assertTrue(fx(() -> composer.lookup(".voice-bar") == null));
        await(() -> !Files.exists(file), "the discarded recording's file deleted"); // discarding is done
        assertTrue(voiceNotes().isEmpty());
    }

    /** The recordings' files there are now. */
    private static java.util.Set<Path> recordings() throws java.io.IOException {
        Path media = app.unichat.settings.Settings.get().cacheDir().resolve("media");
        if (!Files.isDirectory(media)) return new java.util.HashSet<>();
        try (var files = Files.list(media)) {
            return files.filter(f -> f.getFileName().toString().matches("voice-.*\\.ogg"))
                    .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        }
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
        // Longer than the silence that gives a mic away; the bar may give up before the recorder took it all.
        offer(3_000);
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

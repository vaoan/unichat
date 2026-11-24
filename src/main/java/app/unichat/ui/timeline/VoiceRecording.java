package app.unichat.ui.timeline;

import app.unichat.ui.Icons;
import app.unichat.ui.WorkingLine;
import app.unichat.model.VoiceNote;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.voice.Microphone;
import app.unichat.voice.PcmSource;
import app.unichat.voice.VoiceEnhancer;
import app.unichat.voice.VoiceRecorder;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Recording a voice note from the composer: the microphone button, the bar that takes the text box's place while
 * recording, and cleaning the note up and sending it (which takes seconds, so a working line says so).
 */
final class VoiceRecording {
    private static final long MIN_VOICE_MS = 700;

    /** What a recording needs from the message box it replaces. */
    interface Box {
        /** The open chat; null with none. */
        @Nullable String chatId();

        /** The message being replied to, the note answers it; null if none. */
        @Nullable String replyId();

        /** Leaves reply or edit mode, as a sent text does. */
        void cancelMode();

        void focus();

        /** Shows {@code bar} where the text box is; null puts the text box back. */
        void showInstead(@Nullable Node bar);

        /** Sends with {@code send} in {@code chatId}'s turn, behind texts being corrected before they go. */
        void inTurn(String chatId, Runnable send);
    }

    private final ChatService svc;
    private final Consumer<String> onError;
    private final Box box;
    private final Button mic;
    /** "Preparing the voice note… 3 s": cleaning it up takes seconds, and before it's sent nothing else shows. */
    private final WorkingLine preparing = new WorkingLine();
    private @Nullable VoiceBar voiceBar;
    private @Nullable VoiceRecorder recorder;
    private Runnable beforeRecording = () -> { };
    private Callable<PcmSource> microphone = Microphone::open;

    VoiceRecording(ChatService svc, Consumer<String> onError, Box box) {
        this.svc = svc;
        this.onError = onError;
        this.box = box;
        mic = Icons.iconButton(Icons.MIC, I18n.t("composer.record"), this::start);
        mic.getStyleClass().add("mic-button");
    }

    /** The microphone, beside the text box. */
    Button button() { return mic; }

    /** "Preparing the voice note…", above the box. */
    Node status() { return preparing.node(); }

    /** While the bar is there, the box takes no text. */
    boolean recording() { return voiceBar != null; }

    /** Runs before a recording starts: what's playing shouldn't end up in it. */
    void setBeforeRecording(Runnable r) { beforeRecording = r; }

    void setMicrophone(Callable<PcmSource> source) { microphone = source; }

    /** The chat changes under it: what was being recorded goes, and {@code message} says so. */
    void discardIfRecording(String message) {
        if (recorder != null) discard(message);
    }

    private void start() {
        beforeRecording.run();
        mic.setDisable(true);
        // Opening it can wait for the system's permission question.
        Thread.ofPlatform().daemon().name("open-microphone").start(() -> {
            try {
                PcmSource source = microphone.call();
                Path file = Settings.get().cacheDir().resolve("media").resolve("voice-" + UUID.randomUUID() + ".ogg");
                VoiceRecorder r = new VoiceRecorder(source, file);
                Platform.runLater(() -> show(r));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    mic.setDisable(false);
                    onError.accept(e.getMessage() != null ? e.getMessage() : I18n.t("composer.micFailed"));
                });
            }
        });
    }

    private void show(VoiceRecorder r) {
        recorder = r;
        mic.setDisable(false);
        VoiceBar bar = new VoiceBar(r, () -> discard(null), this::send, this::discard);
        voiceBar = bar;
        box.showInstead(bar);
        r.start();
        bar.focusSend();
    }

    private @Nullable VoiceRecorder end() {
        VoiceRecorder r = recorder;
        recorder = null;
        if (voiceBar != null) box.showInstead(null);
        voiceBar = null;
        return r;
    }

    private void discard(@Nullable String message) {
        VoiceRecorder r = end();
        if (r == null) return;
        Thread.ofPlatform().daemon().name("voice-discard").start(() -> {
            try {
                r.cancel();
            } catch (Exception e) {
                System.err.println("Could not discard a recording: " + e.getClass().getSimpleName());
            }
        });
        if (message != null) onError.accept(message);
        box.focus();
    }

    private void send() {
        VoiceRecorder r = end();
        if (r == null) return;
        String toChat = java.util.Objects.requireNonNull(box.chatId(), "a chat changed or closed discards its recording first");
        String reply = box.replyId();
        box.cancelMode();
        box.focus();
        preparing.start(I18n.t("composer.voicePreparing"));
        Thread.ofPlatform().daemon().name("voice-send").start(() -> {
            try {
                VoiceNote note = r.stop();
                if (note.durationMs() < MIN_VOICE_MS) {
                    r.deleteRaw();
                    Files.deleteIfExists(note.file());
                    Platform.runLater(() -> onError.accept(I18n.t("composer.voiceTooShort")));
                    return;
                }
                boolean[] told = {false};
                VoiceNote improved = VoiceEnhancer.improve(note, r.raw(), r.rate(), done -> {
                    if (told[0]) return;
                    told[0] = true;
                    Platform.runLater(() -> onError.accept(I18n.t("composer.installingEnhancer")));
                }, () -> Platform.runLater(() -> onError.accept(I18n.t("composer.voiceNotCleaned"))));
                r.deleteRaw();
                box.inTurn(toChat, () -> svc.sendVoice(toChat, improved, reply).exceptionally(err -> {
                    Platform.runLater(() -> onError.accept(I18n.t("composer.voiceSendFailed")));
                    return null;
                }));
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(I18n.t("composer.voiceSaveFailed")));
            } finally {
                Platform.runLater(preparing::stop); // sent (it shows in the chat) or failed (said so)
            }
        });
    }
}

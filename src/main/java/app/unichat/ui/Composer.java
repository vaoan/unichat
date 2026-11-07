package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.VoiceNote;
import app.unichat.settings.Settings;
import app.unichat.voice.Microphone;
import app.unichat.voice.VoiceEnhancer;
import app.unichat.voice.PcmSource;
import app.unichat.voice.VoiceRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import app.unichat.model.Message;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.support.Errors;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Message input: auto-growing text area, reply/edit banner, attach + emoji + send buttons.
 * Enter sends (Shift+Enter newline) unless the user switched it off in settings. ↑ on an empty
 * input edits your last message, Esc cancels reply/edit. Drafts are kept per chat.
 */
public final class Composer extends VBox {
    private static final long MIN_VOICE_MS = 700;

    private final ChatService svc;
    private final TextArea input = new TextArea();
    private final HBox banner = new HBox(8);
    private final Label bannerTitle = new Label();
    private final Label bannerText = new Label();
    private final Map<String, String> drafts = new HashMap<>();
    private final PauseTransition typingStop = new PauseTransition(Duration.seconds(4));
    private final Consumer<String> onError;
    private String chatId;
    private Message replyTo;
    private Message editing;
    private boolean typingSent;
    private final HBox row;
    private final Button send;
    private final Button mic;
    private AiReplyButton ai;
    private AiReplyButton fix;
    private String fixing;
    /** Holding a key repeats it, and each press after the first would cancel. */
    private KeyCode aiKeyHeld;
    private final VBox aiStatus = new VBox();
    private VoiceBar voiceBar;
    private VoiceRecorder recorder;
    private Runnable beforeRecording = () -> { };
    private Callable<PcmSource> microphone = Microphone::open;

    public Composer(ChatService svc, Consumer<String> onError) {
        this.svc = svc;
        this.onError = onError;
        getStyleClass().add("composer");

        bannerTitle.getStyleClass().add("banner-title");
        bannerText.getStyleClass().add("banner-text");
        VBox bannerBody = new VBox(1, bannerTitle, bannerText);
        HBox.setHgrow(bannerBody, Priority.ALWAYS);
        Button close = Icons.iconButton(Icons.CLOSE, "Cancelar (Esc)", this::cancelMode);
        banner.getChildren().addAll(bannerBody, close);
        banner.getStyleClass().add("composer-banner");
        banner.setAlignment(Pos.CENTER_LEFT);
        Nodes.show(banner, false);

        input.setPromptText("Escribe un mensaje");
        input.setAccessibleText("Escribe un mensaje");
        input.setAccessibleHelp("Enter envía, Shift+Enter nueva línea, flecha arriba edita tu último mensaje, Esc cancela");
        input.setWrapText(true);
        input.setPrefRowCount(1);
        input.getStyleClass().add("composer-input");
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        input.textProperty().addListener((o, a, t) -> {
            autoGrow();
            onTyped(t);
        });
        HBox.setHgrow(input, Priority.ALWAYS);

        Button attach = Icons.iconButton(Icons.ATTACH, "Adjuntar archivo", this::chooseFile);
        Button emoji = Icons.iconButton(Icons.EMOJI, "Emojis", null);
        emoji.setOnAction(e -> {
            if (onPanel != null) onPanel.accept(SidePanel.Tab.EMOJIS);
            else EmojiPicker.show(emoji, this::insert);
        });
        Button stickers = new Button("GIF");
        stickers.getStyleClass().addAll("icon-button", "gif-button");
        stickers.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE); // 160 % text: "GIF", not "…"
        stickers.setAccessibleText("Stickers y GIFs");
        stickers.setTooltip(new javafx.scene.control.Tooltip("Stickers y GIFs"));
        stickers.setOnAction(e -> { if (onPanel != null) onPanel.accept(SidePanel.Tab.STICKERS); });
        Button send = Icons.iconButton(Icons.SEND, "Enviar", this::send);
        send.getStyleClass().add("send-button");
        send.disableProperty().bind(input.textProperty().map(t -> t.isBlank()));

        this.send = send;
        mic = Icons.iconButton(Icons.MIC, "Grabar una nota de voz", this::startRecording);
        mic.getStyleClass().add("mic-button");
        ai = new AiReplyButton(AiReplyButton.REPLY, this::aiRequest, this::aiDraft, onError, this::aiDone, AiReplyButton::replyWriter);
        fix = new AiReplyButton(AiReplyButton.FIX, this::fixRequest, this::fixed, onError, this::aiDone, AiReplyButton::fixWriter);
        row = new HBox(4, attach, input, ai.node(), fix.node(), stickers, emoji, mic, send);
        row.setAlignment(Pos.BOTTOM_CENTER);
        row.getStyleClass().add("composer-row");
        aiStatus.getChildren().addAll(ai.status(), fix.status());
        getChildren().addAll(banner, aiStatus, row);
        input.textProperty().addListener((o, a, t) -> refreshVoice());
        refreshVoice();

        setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        setOnDragDropped(e -> {
            sendFiles(e.getDragboard().getFiles());
            e.setDropCompleted(true);
            e.consume();
        });
        typingStop.setOnFinished(e -> stopTyping());
    }

    public void setChat(String newChatId) {
        if (chatId != null) {
            stopTyping();
            drafts.put(chatId, editing == null ? input.getText() : "");
        }
        if (recorder != null) discardRecording("Grabación descartada al cambiar de chat");
        chatId = newChatId;
        replyTo = null;
        editing = null;
        updateBanner();
        input.setText(drafts.getOrDefault(chatId, ""));
        input.positionCaret(input.getLength());
    }

    public void focus() { input.requestFocus(); }

    /** The microphone takes the send button's place while there's nothing written and this chat takes voice notes. */
    void refreshVoice() {
        Chat c = chatId == null ? null : svc.chat(chatId);
        boolean offer = c != null && c.features().voiceSend() != null && input.getText().isBlank() && editing == null;
        Nodes.show(mic, offer);
        Nodes.show(send, !offer);
    }

    /** Runs before a recording starts: what's playing shouldn't end up in it. */
    void setBeforeRecording(Runnable r) { beforeRecording = r; }

    void setMicrophone(Callable<PcmSource> source) { microphone = source; }

    private void startRecording() {
        beforeRecording.run();
        mic.setDisable(true);
        // Opening it can wait for the system's permission question.
        Thread.ofPlatform().daemon().name("open-microphone").start(() -> {
            try {
                PcmSource source = microphone.call();
                Path file = Settings.get().cacheDir().resolve("media").resolve("voice-" + java.util.UUID.randomUUID() + ".ogg");
                VoiceRecorder r = new VoiceRecorder(source, file);
                Platform.runLater(() -> showRecording(r));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    mic.setDisable(false);
                    onError.accept(e.getMessage() != null ? e.getMessage() : "No se pudo usar el micrófono");
                });
            }
        });
    }

    private void showRecording(VoiceRecorder r) {
        recorder = r;
        mic.setDisable(false);
        voiceBar = new VoiceBar(r, () -> discardRecording(null), this::sendRecording, this::discardRecording);
        getChildren().set(getChildren().indexOf(row), voiceBar);
        r.start();
        voiceBar.focusSend();
    }

    private VoiceRecorder endRecordingUi() {
        VoiceRecorder r = recorder;
        recorder = null;
        if (voiceBar != null) getChildren().set(getChildren().indexOf(voiceBar), row);
        voiceBar = null;
        return r;
    }

    private void discardRecording(String message) {
        VoiceRecorder r = endRecordingUi();
        if (r == null) return;
        Thread.ofPlatform().daemon().name("voice-discard").start(() -> {
            try {
                r.cancel();
            } catch (Exception e) {
                System.err.println("Could not discard a recording: " + e.getClass().getSimpleName());
            }
        });
        if (message != null) onError.accept(message);
        focus();
    }

    private void sendRecording() {
        VoiceRecorder r = endRecordingUi();
        if (r == null) return;
        String toChat = chatId;
        String reply = replyTo == null ? null : replyTo.id();
        cancelMode();
        focus();
        Thread.ofPlatform().daemon().name("voice-send").start(() -> {
            try {
                VoiceNote note = r.stop();
                if (note.durationMs() < MIN_VOICE_MS) {
                    r.deleteRaw();
                    Files.deleteIfExists(note.file());
                    Platform.runLater(() -> onError.accept("La nota de voz es demasiado corta"));
                    return;
                }
                boolean[] told = {false};
                VoiceNote improved = VoiceEnhancer.improve(note, r.raw(), r.rate(), done -> {
                    if (told[0]) return;
                    told[0] = true;
                    Platform.runLater(() -> onError.accept("Instalando la mejora de audio; solo pasa la primera vez…"));
                });
                r.deleteRaw();
                svc.sendVoice(toChat, improved, reply).exceptionally(err -> {
                    Platform.runLater(() -> onError.accept("No se pudo enviar la nota de voz"));
                    return null;
                });
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept("No se pudo guardar la nota de voz"));
            }
        });
    }

    /** Tests: a stand-in for the AI program. */
    void useAiWriter(java.util.function.Supplier<AiReplyButton.Writer> writers) {
        HBox parent = row;
        int at = parent.getChildren().indexOf(ai.node());
        ai = new AiReplyButton(AiReplyButton.REPLY, this::aiRequest, this::aiDraft, onError, this::aiDone, writers);
        parent.getChildren().set(at, ai.node());
        aiStatus.getChildren().set(0, ai.status());
    }

    /** Tests: a stand-in for the AI program that corrects. */
    void useFixWriter(java.util.function.Supplier<AiReplyButton.Writer> writers) {
        int at = row.getChildren().indexOf(fix.node());
        fix = new AiReplyButton(AiReplyButton.FIX, this::fixRequest, this::fixed, onError, this::aiDone, writers);
        row.getChildren().set(at, fix.node());
        aiStatus.getChildren().set(1, fix.status());
    }

    /** Only what's written goes: not the chat, nor the instructions for replies. Null with nothing written. */
    private AiReplyButton.Request fixRequest() {
        if (chatId == null || input.getText().isBlank()) return null;
        fixing = input.getText();
        return new AiReplyButton.Request(chatId, app.unichat.assist.FixPrompt.build(fixing));
    }

    /** Replaces the text only if it's still what was sent: the person may have kept writing. ⌘Z brings it back. */
    private void fixed(String forChat, String result) {
        String corrected = app.unichat.assist.FixPrompt.keepWrapping(fixing, result);
        if (!forChat.equals(chatId)) {
            if (fixing.equals(drafts.get(forChat))) {
                drafts.put(forChat, corrected);
                onError.accept("Texto corregido, en el chat donde lo escribiste");
            }
        } else if (!input.getText().equals(fixing)) {
            onError.accept("El texto cambió mientras se corregía: quedó como lo dejaste");
        } else if (corrected.equals(fixing.strip())) {
            onError.accept("El texto ya estaba bien");
        } else {
            input.replaceText(0, input.getLength(), corrected);
            input.positionCaret(input.getLength());
            onError.accept("Texto corregido. " + Shortcuts.key("Z") + " lo deja como estaba");
        }
    }

    /** The chat so far, the message chosen with "Responder" if any, and what's written as the idea. Null with nothing to answer. */
    private AiReplyButton.Request aiRequest() {
        java.util.List<Message> chat = chatId == null ? java.util.List.of() : svc.messages(chatId);
        if (chat.isEmpty()) return null;
        return new AiReplyButton.Request(chatId, AiReplyButton.prompt(chat, replyTo, input.getText()));
    }

    /** Into the box of the chat it was for: what was written there went into the prompt as the idea. */
    private void aiDraft(String forChat, String draft) {
        if (!forChat.equals(chatId)) {
            drafts.put(forChat, draft);
            onError.accept("Borrador listo en el chat donde lo pediste");
            return;
        }
        input.setText(draft);
        input.positionCaret(input.getLength());
        onError.accept("Borrador listo: revísalo antes de enviarlo");
    }

    public void reply(Message m) {
        editing = null;
        replyTo = m;
        updateBanner();
        focus();
    }

    /** What a sticker or GIF sent now answers, and the composer leaves reply mode as a sent text would; null if none. */
    String takeReply() {
        if (replyTo == null) return null;
        String id = replyTo.id();
        cancelMode();
        return id;
    }

    public void edit(Message m) {
        replyTo = null;
        editing = m;
        input.setText(m.body());
        input.positionCaret(input.getLength());
        updateBanner();
        focus();
    }

    private Consumer<SidePanel.Tab> onPanel;

    /** The emoji and sticker/GIF buttons open the side panel on that tab. */
    public void setOnPanel(Consumer<SidePanel.Tab> onPanel) {
        this.onPanel = onPanel;
    }

    public void insert(String text) {
        input.insertText(input.getCaretPosition(), text);
        focus();
    }

    public void sendFiles(List<File> files) {
        if (chatId == null) return;
        String reply = replyTo == null ? null : replyTo.id();
        for (File f : files) svc.sendFile(chatId, f.toPath(), reply).exceptionally(this::fail);
        cancelMode();
    }

    private void chooseFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Adjuntar archivo");
        List<File> files = fc.showOpenMultipleDialog(getScene().getWindow());
        if (files != null) sendFiles(files);
    }

    /** ⌘I writes and ⌘O corrects, from anywhere in the window. True if it's one of them, with the AI buttons on. */
    boolean aiKeyPressed(KeyCode key) {
        if ((key != KeyCode.O && key != KeyCode.I) || !AiReplyButton.SHOWN.get()) return false;
        if (aiKeyHeld != key) (key == KeyCode.O ? fix : ai).node().fire();
        aiKeyHeld = key;
        return true;
    }

    void aiKeyReleased(KeyCode key) {
        if (key == aiKeyHeld) aiKeyHeld = null;
    }

    /** It finished, so the text box gets the keys: Enter sends. Unless the person moved to another chat meanwhile. */
    private void aiDone(String forChat) {
        if (forChat == null || forChat.equals(chatId)) focus();
    }

    private void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER) {
            boolean sendOnEnter = Settings.get().sendOnEnter();
            boolean modifier = e.isShortcutDown();
            if ((sendOnEnter && !e.isShiftDown() && !e.isAltDown()) || (!sendOnEnter && modifier)) {
                e.consume();
                send();
            } else if (e.isShiftDown() || e.isAltDown() || modifier) {
                e.consume();
                input.insertText(input.getCaretPosition(), "\n");
            }
        } else if ((e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) && e.isShiftDown()
                && !e.isShortcutDown() && !e.isAltDown()) {
            // The anchor stays: the other arrow then runs from the same place to the other end.
            e.consume();
            input.selectRange(input.getAnchor(), e.getCode() == KeyCode.UP ? 0 : input.getLength());
        } else if (e.getCode() == KeyCode.ESCAPE && (replyTo != null || editing != null)) {
            e.consume();
            cancelMode();
        } else if (e.getCode() == KeyCode.UP && input.getText().isEmpty() && chatId != null) {
            List<Message> msgs = svc.messages(chatId);
            for (int i = msgs.size() - 1; i >= 0; i--) {
                Message m = msgs.get(i);
                if (m.outgoing() && !m.deleted() && !m.isMedia()) {
                    e.consume();
                    edit(m);
                    return;
                }
            }
        }
    }

    private void send() {
        String text = input.getText().strip();
        if (text.isEmpty() || chatId == null) return;
        if (editing != null) {
            if (!text.equals(editing.body())) svc.editMessage(chatId, editing.id(), text).exceptionally(this::fail);
        } else {
            svc.sendText(chatId, text, replyTo == null ? null : replyTo.id()).exceptionally(this::fail);
        }
        input.clear();
        drafts.remove(chatId);
        cancelMode();
        stopTyping();
    }

    private Void fail(Throwable t) {
        Platform.runLater(() -> onError.accept("No se pudo enviar: " + Errors.message(t)));
        return null;
    }

    private void cancelMode() {
        if (editing != null) input.clear();
        replyTo = null;
        editing = null;
        updateBanner();
    }

    private void updateBanner() {
        boolean show = replyTo != null || editing != null;
        Nodes.show(banner, show);
        if (replyTo != null) {
            bannerTitle.setText("Respondiendo a " + (replyTo.outgoing() ? "ti mismo" : replyTo.senderName()));
            bannerText.setText(replyTo.preview());
        } else if (editing != null) {
            bannerTitle.setText("Editando mensaje");
            bannerText.setText(editing.preview());
        }
    }

    private void onTyped(String text) {
        if (chatId == null || editing != null || !Settings.get().sendTypingNotifications()) return;
        if (text.isEmpty()) {
            stopTyping();
            return;
        }
        if (!typingSent) {
            svc.sendTyping(chatId, true);
            typingSent = true;
        }
        typingStop.playFromStart();
    }

    private void stopTyping() {
        typingStop.stop();
        if (typingSent && chatId != null) svc.sendTyping(chatId, false);
        typingSent = false;
    }

    /** Grows from 1 to 8 lines with content, then scrolls. */
    private void autoGrow() {
        Text measure = new Text(input.getText().isEmpty() ? " " : input.getText());
        measure.setFont(input.getFont());
        double width = Math.max(50, input.getWidth() - 24);
        measure.setWrappingWidth(width);
        double lineH = input.getFont().getSize() * 1.35;
        int lines = (int) Math.ceil(measure.getLayoutBounds().getHeight() / lineH);
        input.setPrefRowCount(Math.max(1, Math.min(8, lines)));
    }
}

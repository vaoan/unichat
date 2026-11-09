package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.VoiceNote;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
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
import javafx.scene.layout.Region;
import javafx.scene.Node;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import app.unichat.service.SendAs;
import app.unichat.service.UploadQueue;
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
    /** The AI's draft in each chat, to compare with what's sent. */
    private final app.unichat.assist.DraftCorrections aiDrafts = new app.unichat.assist.DraftCorrections();
    /** The program that learns how the person writes; tests put theirs, as for drafts. */
    private volatile java.util.function.Supplier<app.unichat.assist.StyleLearner.Program> learning =
            () -> app.unichat.assist.StyleLearner.program(app.unichat.assist.AiSettings.engine());
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
    /** The rounded box: one line (row), or the text above the buttons when they would leave it too little room. */
    private final VBox frame;
    private final Region rowGrow = new Region();
    private boolean stacked;
    /** Held here: the global properties only keep a weak reference, so a closed chat's composer can go. */
    private final javafx.beans.InvalidationListener frameFit;
    /** Below this many characters' width the text goes on its own line (160 % text, a narrow window, the info panel). */
    private static final double MIN_INPUT_EM = 14;
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
        Button close = Icons.iconButton(Icons.CLOSE, I18n.t("composer.cancelEsc"), this::cancelMode);
        banner.getChildren().addAll(bannerBody, close);
        banner.getStyleClass().add("composer-banner");
        banner.setAlignment(Pos.CENTER_LEFT);
        Nodes.show(banner, false);

        input.setPromptText(I18n.t("composer.placeholder"));
        input.setAccessibleText(I18n.t("composer.placeholder"));
        input.setAccessibleHelp(I18n.t("composer.help"));
        input.setWrapText(true);
        input.setPrefRowCount(1);
        input.getStyleClass().add("composer-input");
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        input.textProperty().addListener((o, a, t) -> {
            autoGrow();
            onTyped(t);
        });
        HBox.setHgrow(input, Priority.ALWAYS);

        Button attach = Icons.iconButton(Icons.ATTACH, I18n.t("composer.attach"), null);
        attach.setOnAction(e -> attachMenu().show(attach, javafx.geometry.Side.TOP, 0, -4));
        // One button for emojis, stickers and GIFs: opens the panel on whichever of them was used last.
        Button emoji = Icons.iconButton(Icons.EMOJI, I18n.t("composer.mediaPanel"), null);
        emoji.setOnAction(e -> {
            if (onPanel != null) onPanel.accept(SidePanel.lastMediaTab());
            else EmojiPicker.show(emoji, this::insert);
        });
        Button send = Icons.iconButton(Icons.SEND, I18n.t("composer.send"), this::send);
        send.getStyleClass().add("send-button");
        send.disableProperty().bind(input.textProperty().map(t -> t.isBlank()));

        this.send = send;
        mic = Icons.iconButton(Icons.MIC, I18n.t("composer.record"), this::startRecording);
        mic.getStyleClass().add("mic-button");
        ai = new AiReplyButton(AiReplyButton.REPLY, this::aiRequest, this::aiDraft, onError, this::aiDone, AiReplyButton::replyWriter);
        fix = new AiReplyButton(AiReplyButton.FIX, this::fixRequest, this::fixed, onError, this::aiDone, AiReplyButton::fixWriter);
        row = new HBox(4, attach, input, ai.node(), fix.node(), emoji, mic, send);
        row.setAlignment(Pos.BOTTOM_CENTER);
        frame = new VBox(2, row);
        frame.getStyleClass().add("composer-row");
        // Again when the room changes: the width, the text size (icons grow with it), the AI buttons coming or going.
        javafx.beans.InvalidationListener fit = o -> stack(frame.getWidth() < buttonsWidth() + MIN_INPUT_EM * input.getFont().getSize());
        frame.widthProperty().addListener(fit);
        Icons.SCALE.addListener(new javafx.beans.WeakInvalidationListener(fit));
        AiReplyButton.SHOWN.addListener(new javafx.beans.WeakInvalidationListener(fit));
        frameFit = fit;
        aiStatus.getChildren().addAll(ai.status(), fix.status());
        getChildren().addAll(banner, aiStatus, frame);
        input.textProperty().addListener((o, a, t) -> refreshVoice());
        input.textProperty().addListener((o, a, t) -> { if (t.isBlank()) aiDrafts.forget(chatId); }); // a draft thrown away
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
        if (recorder != null) discardRecording(I18n.t("composer.recordingDiscarded"));
        chatId = newChatId;
        replyTo = null;
        editing = null;
        updateBanner();
        input.setText(drafts.getOrDefault(chatId, ""));
        input.positionCaret(input.getLength());
    }

    public void focus() { input.requestFocus(); }

    /** What the buttons beside the text take, as they are now (the AI ones and the mic come and go). */
    private double buttonsWidth() {
        double w = row.getSpacing() * (row.getChildren().size() - 1) + frame.getInsets().getLeft() + frame.getInsets().getRight();
        for (Node n : row.getChildren()) {
            if (n != input && n != rowGrow && n.isManaged()) w += n.prefWidth(-1);
        }
        return w;
    }

    private void stack(boolean on) {
        if (on == stacked) return;
        stacked = on;
        boolean focused = input.isFocused();
        if (on) {
            row.getChildren().remove(input);
            HBox.setHgrow(rowGrow, Priority.ALWAYS);
            row.getChildren().add(1, rowGrow);
            frame.getChildren().add(0, input);
            // On its own line nothing stretches it any more: at least as tall as the buttons were, or a scroll bar shows.
            input.minHeightProperty().bind(row.heightProperty());
        } else {
            input.minHeightProperty().unbind();
            input.setMinHeight(Region.USE_COMPUTED_SIZE);
            frame.getChildren().remove(input);
            row.getChildren().remove(rowGrow);
            row.getChildren().add(1, input);
        }
        if (focused) input.requestFocus();
    }

    /**
     * A key typed elsewhere in the window writes here (as in Telegram): takes the focus with the caret at the end, so
     * the character lands in the box. False while it can't take text (recording a voice note).
     */
    boolean takeTyping() {
        if (chatId == null || !input.isVisible() || input.isDisabled() || voiceBar != null) return false;
        input.requestFocus();
        input.end();
        return true;
    }

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
                    onError.accept(e.getMessage() != null ? e.getMessage() : I18n.t("composer.micFailed"));
                });
            }
        });
    }

    private void showRecording(VoiceRecorder r) {
        recorder = r;
        mic.setDisable(false);
        voiceBar = new VoiceBar(r, () -> discardRecording(null), this::sendRecording, this::discardRecording);
        getChildren().set(getChildren().indexOf(frame), voiceBar);
        r.start();
        voiceBar.focusSend();
    }

    private VoiceRecorder endRecordingUi() {
        VoiceRecorder r = recorder;
        recorder = null;
        if (voiceBar != null) getChildren().set(getChildren().indexOf(voiceBar), frame);
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
                    Platform.runLater(() -> onError.accept(I18n.t("composer.voiceTooShort")));
                    return;
                }
                boolean[] told = {false};
                VoiceNote improved = VoiceEnhancer.improve(note, r.raw(), r.rate(), done -> {
                    if (told[0]) return;
                    told[0] = true;
                    Platform.runLater(() -> onError.accept(I18n.t("composer.installingEnhancer")));
                });
                r.deleteRaw();
                svc.sendVoice(toChat, improved, reply).exceptionally(err -> {
                    Platform.runLater(() -> onError.accept(I18n.t("composer.voiceSendFailed")));
                    return null;
                });
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(I18n.t("composer.voiceSaveFailed")));
            }
        });
    }

    /** Tests: a stand-in for the AI program. */
    void useAiWriter(java.util.function.Supplier<AiReplyButton.Writer> writers) {
        learning = () -> {
            AiReplyButton.Writer w = writers.get();
            return new app.unichat.assist.StyleLearner.Program() {
                @Override public String write(String prompt) throws app.unichat.assist.ReplyWriter.Failure { return w.write(prompt); }

                @Override public void cancel() { w.cancel(); }
            };
        };
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
        String text = fixing;
        return new AiReplyButton.Request(chatId, () -> app.unichat.assist.FixPrompt.build(text, app.unichat.assist.WritingStyle.get().text()));
    }

    /** Replaces the text only if it's still what was sent: the person may have kept writing. ⌘Z brings it back. */
    private void fixed(String forChat, String result) {
        String corrected = app.unichat.assist.FixPrompt.keepWrapping(fixing, result);
        if (!forChat.equals(chatId)) {
            if (fixing.equals(drafts.get(forChat))) {
                drafts.put(forChat, corrected);
                onError.accept(I18n.t("composer.fixedElsewhere"));
            }
        } else if (!input.getText().equals(fixing)) {
            onError.accept(I18n.t("composer.fixChanged"));
        } else if (corrected.equals(fixing.strip())) {
            onError.accept(I18n.t("composer.fixNothing"));
        } else {
            input.replaceText(0, input.getLength(), corrected);
            input.positionCaret(input.getLength());
            onError.accept(I18n.t("composer.fixed", Shortcuts.key("Z")));
        }
    }

    /** The chat so far, the message chosen with "Responder" if any, and what's written as the idea. Null with nothing to answer. */
    private AiReplyButton.Request aiRequest() {
        java.util.List<Message> chat = chatId == null ? java.util.List.of() : java.util.List.copyOf(svc.messages(chatId));
        if (chat.isEmpty()) return null;
        Message target = replyTo;
        String idea = input.getText();
        // Voice notes being answered are transcribed first (Whisper, here), so the draft knows what they said.
        java.util.List<Message> toHear = app.unichat.assist.AiSettings.hearVoiceNotes()
                ? app.unichat.assist.ReplyPrompt.voiceNotesToHear(chat, target) : java.util.List.of();
        String forChat = chatId;
        app.unichat.assist.StyleLearner.Program program = learning.get();
        return new AiReplyButton.Request(chatId, () -> {
            String style = AiReplyButton.style(forChat, chat, program); // first learn how the person writes, then the draft
            return AiReplyButton.prompt(chat, target, idea, AiReplyButton.heard(svc, chat, toHear), style);
        }, program::cancel);
    }

    /** Into the box of the chat it was for: what was written there went into the prompt as the idea. */
    private void aiDraft(String forChat, String draft) {
        aiDrafts.drafted(forChat, draft); // what's sent instead teaches how the person writes
        if (!forChat.equals(chatId)) {
            drafts.put(forChat, draft);
            onError.accept(I18n.t("composer.draftElsewhere"));
            return;
        }
        input.setText(draft);
        input.positionCaret(input.getLength());
        onError.accept(I18n.t("composer.draftReady"));
    }

    /**
     * Answers {@code m} with a draft: it becomes the message being replied to, and what's already in the box is the
     * base of the draft (ReplyPrompt). Nothing new starts while a draft is being written.
     */
    void replyWithAi(Message m) {
        reply(m);
        if (AiReplyButton.SHOWN.get() && !ai.busy()) ai.node().fire();
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

    /** Dropped files: one goes as it is, several as a batch (one at a time, with the upload bar). */
    public void sendFiles(List<File> files) {
        sendFiles(files, SendAs.AS_IS);
    }

    /** {@code as}: as they are, as photos or as documents. */
    public void sendFiles(List<File> files, SendAs as) {
        send(files, as, false);
    }

    /** {@code batch}: through the upload queue even if it's a single file. Only the first file answers the reply. */
    private void send(List<File> files, SendAs as, boolean batch) {
        if (chatId == null || files == null || files.isEmpty()) return;
        String reply = replyTo == null ? null : replyTo.id();
        if (files.size() == 1 && !batch) {
            svc.sendFile(chatId, files.get(0).toPath(), reply, as, p -> { }).exceptionally(this::fail);
        } else {
            List<UploadQueue.Upload> uploads = new java.util.ArrayList<>();
            for (File f : files) uploads.add(new UploadQueue.Upload(chatId, f.toPath(), uploads.isEmpty() ? reply : null, as));
            UploadQueue.of(svc).addAll(uploads);
        }
        cancelMode();
    }

    /**
     * As in WhatsApp and Telegram: a file as it is, photos and videos (pictures made into photos the network shows as
     * such, several at once), a document (the original, never a photo), or many files or many photos one by one.
     */
    private javafx.scene.control.ContextMenu attachMenu() {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(
                attachItem("composer.attach.file", Icons.ATTACH, () -> choose(false, SendAs.AS_IS, false)),
                attachItem("composer.attach.photo", Icons.IMAGE, () -> choose(true, SendAs.PHOTO, false)),
                attachItem("composer.attach.document", Icons.FILE, () -> choose(false, SendAs.DOCUMENT, false)),
                attachItem("composer.attach.batch", Icons.UPLOAD, () -> choose(false, SendAs.AS_IS, true)),
                attachItem("composer.attach.photoBatch", Icons.CAMERA, () -> choose(true, SendAs.PHOTO, true)));
        menu.getStyleClass().add("attach-menu");
        return menu;
    }

    private static javafx.scene.control.MenuItem attachItem(String key, String icon, Runnable action) {
        javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(I18n.t(key), Icons.icon(icon, 16));
        item.setOnAction(e -> action.run());
        return item;
    }

    /** {@code photos}: only pictures and videos, several allowed; {@code batch}: as many as wanted, one at a time. */
    private void choose(boolean photos, SendAs as, boolean batch) {
        FileChooser fc = new FileChooser();
        fc.setTitle(I18n.t(batch && photos ? "composer.attach.photoBatch" : batch ? "composer.attach.batch"
                : photos ? "composer.attach.photo" : "composer.attach"));
        if (photos) fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.t("composer.attach.photos"),
                "*.jpg", "*.jpeg", "*.png", "*.gif", "*.webp", "*.heic", "*.bmp", "*.mp4", "*.mov", "*.m4v", "*.webm", "*.mkv"));
        javafx.stage.Window w = getScene().getWindow();
        if (batch || photos) {
            send(fc.showOpenMultipleDialog(w), as, batch);
        } else {
            File f = fc.showOpenDialog(w);
            if (f != null) send(List.of(f), as, false);
        }
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
            app.unichat.assist.StylePrompt.Correction c = aiDrafts.sent(chatId, text);
            if (c != null) Thread.ofVirtual().start(() -> {
                app.unichat.assist.WritingStyle style = app.unichat.assist.WritingStyle.get();
                style.addCorrection(c.draft(), c.sent());
                style.writtenByAi(c.sent()); // came from a draft: not to be learned from as their own writing
            });
        }
        input.clear();
        drafts.remove(chatId);
        cancelMode();
        stopTyping();
    }

    private Void fail(Throwable t) {
        Platform.runLater(() -> onError.accept(I18n.t("composer.sendFailed", Errors.message(t))));
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
            bannerTitle.setText(replyTo.outgoing() ? I18n.t("composer.replyingSelf") : I18n.t("composer.replying", replyTo.senderName()));
            bannerText.setText(replyTo.preview());
        } else if (editing != null) {
            bannerTitle.setText(I18n.t("composer.editing"));
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

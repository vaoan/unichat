package app.unichat.ui.timeline;

import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Icons;
import app.unichat.ui.Nodes;
import app.unichat.ui.chats.SidePanel;
import app.unichat.ui.emoji.EmojiPicker;
import app.unichat.model.Chat;
import app.unichat.support.I18n;
import app.unichat.voice.PcmSource;
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
import javafx.scene.control.ScrollPane;
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
import org.jspecify.annotations.Nullable;

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
    private final ChatService svc;
    /** Shift+↑ / Shift+↓: a line at a time ({@link LineSelection}). */
    private final LineSelection lineSelection;
    private final TextArea input = new TextArea() {
        @Override public void paste() { // ⌘V and the menu's Paste alike
            if (!pasteMedia()) super.paste();
        }

        @Override protected void layoutChildren() { // the text is wrapped to the new width here: measure it after
            super.layoutChildren();
            autoGrow();
        }
    };
    private final HBox banner = new HBox(8);
    /** Above the box: what the AI, the voice and the queue of texts being corrected are doing. */
    private final VBox statuses = new VBox();
    private final Label bannerTitle = new Label();
    private final Label bannerText = new Label();
    private final Map<String, String> drafts = new HashMap<>();
    private final PauseTransition typingStop = new PauseTransition(Duration.seconds(4));
    private final Consumer<String> onError;
    /** Where a file or a voice note goes in its chat's turn; by default at once. */
    private InTurn inTurn = (chat, go) -> go.run();

    /** Sending something in its chat's turn, behind what's waiting there (texts being corrected before they go). */
    @FunctionalInterface
    interface InTurn {
        void send(String chatId, Runnable send);
    }

    /** What happens to a text before it goes; by default nothing, and it goes. */
    private Sending sending = (chat, text, replyId) -> false;

    /** A text about to be sent from the box (not an edit): what happens to it first. */
    @FunctionalInterface
    interface Sending {
        /** True when it took {@code text} (to send it later, corrected); false: it goes now. */
        boolean before(String chatId, String text, @Nullable String replyToId);
    }
    private @Nullable String chatId;
    private @Nullable Message replyTo;
    private @Nullable Message editing;
    private boolean typingSent;
    private final HBox row;
    /** The rounded box: one line (row), or the text above the buttons when they would leave it too little room. */
    private final VBox frame;
    private final Region rowGrow = new Region();
    private boolean stacked;
    /** Held here: the global properties only keep a weak reference, so a closed chat's composer can go. */
    @SuppressWarnings("UnusedVariable") // held, never read: that's what keeps the weak listeners' target alive
    private final javafx.beans.InvalidationListener frameFit; // NOPMD: UnusedPrivateField, as above
    /** Below this many characters' width the text goes on its own line (160 % text, a narrow window, the info panel). */
    private static final double MIN_INPUT_EM = 14;
    private final Button send;
    private final VoiceRecording voice;
    private final ComposerAi ai;
    /** The rounded box, or the voice bar while recording: what's in its place. */
    private Node boxShown;

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

        // With the AI on, the empty box says what the marks do ([+], [-], [a fact]). A binding: it holds SHOWN, which lives
        // as long as the app, only weakly, so a closed window's box isn't kept.
        input.promptTextProperty().bind(javafx.beans.binding.Bindings.when(AiReplyButton.SHOWN)
                .then(I18n.t("composer.placeholderAi")).otherwise(I18n.t("composer.placeholder")));
        input.setAccessibleText(I18n.t("composer.placeholder"));
        input.setAccessibleHelp(I18n.t("composer.help"));
        input.setWrapText(true);
        input.setPrefRowCount(1);
        input.getStyleClass().add("composer-input");
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        lineSelection = new LineSelection(input);
        input.setContextMenu(FormatMenu.of(input, this::features));
        input.textProperty().addListener((o, a, t) -> onTyped(t));

        HBox.setHgrow(input, Priority.ALWAYS);

        Button attach = Icons.iconButton(Icons.ATTACH, I18n.t("composer.attach"), null);
        attach.setOnAction(e -> attachMenu().show(attach, javafx.geometry.Side.TOP, 0, -4));
        // One button for emojis, stickers and GIFs: opens the panel on whichever of them was used last.
        Button emoji = Icons.iconButton(Icons.EMOJI, I18n.t("composer.mediaPanel"), null);
        emoji.setOnAction(e -> {
            if (onPanel != null) onPanel.accept(SidePanel.lastMediaTab());
            else EmojiPicker.show(emoji, this::insert);
        });
        Button button = Icons.iconButton(Icons.SEND, I18n.t("composer.send"), this::send);
        button.getStyleClass().add("send-button");
        button.disableProperty().bind(input.textProperty().map(String::isBlank));

        this.send = button;
        voice = new VoiceRecording(svc, onError, new VoiceRecording.Box() {
            @Override public @Nullable String chatId() { return chatId; }

            @Override public @Nullable String replyId() { return replyTo == null ? null : replyTo.id(); }

            @Override public void cancelMode() { Composer.this.cancelMode(); }

            @Override public void focus() { Composer.this.focus(); }

            @Override public void showInstead(@Nullable Node bar) { Composer.this.showInstead(bar); }

            @Override public void inTurn(String chat, Runnable go) { inTurn.send(chat, go); }
        });
        ai = new ComposerAi(svc, input, drafts, onError, new ComposerAi.Box() {
            @Override public @Nullable String chatId() { return chatId; }

            @Override public @Nullable Message replyTo() { return replyTo; }

            @Override public void reply(Message m) { Composer.this.reply(m); }

            @Override public void focus() { Composer.this.focus(); }
        });
        row = new HBox(4, attach, input, ai.replyButton(), ai.fixButton(), emoji, voice.button(), send);
        row.setAlignment(Pos.BOTTOM_CENTER);
        frame = new VBox(2, row);
        frame.getStyleClass().add("composer-row");
        // Again when the room changes: the width, the text size (icons grow with it), the AI buttons coming or going.
        // Decided right after the layout that changed the width, not during it: moving the text box mid-layout (the window
        // coming back from a notification's click) left the frame at the stacked height with the text beside the buttons.
        FxCoalescer refit = new FxCoalescer(() -> stack(frame.getWidth() < buttonsWidth() + MIN_INPUT_EM * input.getFont().getSize()));
        javafx.beans.InvalidationListener fit = o -> refit.request();
        frame.widthProperty().addListener(fit);
        Icons.SCALE.addListener(new javafx.beans.WeakInvalidationListener(fit));
        AiReplyButton.SHOWN.addListener(new javafx.beans.WeakInvalidationListener(fit));
        frameFit = fit;
        statuses.getChildren().addAll(ai.statuses());
        statuses.getChildren().add(voice.status());
        boxShown = frame;
        getChildren().addAll(banner, statuses, frame);
        input.textProperty().addListener((o, a, t) -> refreshVoice());
        refreshVoice();

        acceptDrops(this);
        typingStop.setOnFinished(e -> stopTyping());
    }

    /** Files dropped on {@code target} (this box, the timeline above it) are sent to the open chat. */
    void acceptDrops(Node target) {
        target.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        target.setOnDragDropped(e -> {
            sendFiles(e.getDragboard().getFiles());
            e.setDropCompleted(true);
            e.consume();
        });
    }

    /** {@code bar} where the rounded box is (recording a voice note); null puts the box back. */
    private void showInstead(@Nullable Node bar) {
        Node now = bar == null ? frame : bar;
        getChildren().set(getChildren().indexOf(boxShown), now);
        boxShown = now;
    }

    public void setChat(@Nullable String newChatId) {
        if (chatId != null) {
            stopTyping();
            drafts.put(chatId, editing == null ? input.getText() : "");
        }
        voice.discardIfRecording(I18n.t("composer.recordingDiscarded"));
        chatId = newChatId;
        replyTo = null;
        editing = null;
        updateBanner();
        input.setText(drafts.getOrDefault(chatId, ""));
        input.positionCaret(input.getLength());
        hearAhead();
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
            input.setMinHeight(USE_COMPUTED_SIZE);
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
    public boolean takeTyping() {
        if (chatId == null || !input.isVisible() || input.isDisabled() || voice.recording()) return false;
        input.requestFocus();
        input.end();
        return true;
    }

    /** The microphone takes the send button's place while there's nothing written and this chat takes voice notes. */
    void refreshVoice() {
        Chat c = chatId == null ? null : svc.chat(chatId);
        boolean offer = c != null && c.features().voiceSend() != null && input.getText().isBlank() && editing == null;
        Nodes.show(voice.button(), offer);
        Nodes.show(send, !offer);
    }

    /** Runs before a recording starts: what's playing shouldn't end up in it. */
    void setBeforeRecording(Runnable r) { voice.setBeforeRecording(r); }

    void setMicrophone(Callable<PcmSource> source) { voice.setMicrophone(source); }

    /** Tests: a stand-in for the AI program. */
    void useAiWriter(java.util.function.Supplier<AiReplyButton.Writer> writers) { ai.useAiWriter(writers); }

    /** Tests: a stand-in for the AI program that corrects. */
    void useFixWriter(java.util.function.Supplier<AiReplyButton.Writer> writers) { ai.useFixWriter(writers); }

    /** Transcribes now the voice notes a draft of the open chat would answer ({@link ComposerAi#hearAhead}). */
    void hearAhead() { ai.hearAhead(); }

    /** A draft for {@code forChat} has arrived (package-visible for tests). */
    void aiDraft(String forChat, String draft) { ai.aiDraft(forChat, draft); }

    /** Answers {@code m} with a draft ({@link ComposerAi#replyWithAi}). */
    void replyWithAi(Message m) { ai.replyWithAi(m); }

    public void reply(Message m) {
        editing = null;
        replyTo = m;
        updateBanner();
        focus();
    }

    /** What a sticker or GIF sent now answers, and the composer leaves reply mode as a sent text would; null if none. */
    @Nullable String takeReply() {
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

    private @Nullable Consumer<SidePanel.Tab> onPanel;
    private @Nullable Runnable onProtect;

    /** "Foto protegida contra IA" in the clip's menu: the window asks how hard ({@link CloakSend}). */
    public void setOnProtect(Runnable onProtect) {
        this.onProtect = onProtect;
    }

    /** The media button opens the side panel on that tab. */
    public void setOnPanel(Consumer<SidePanel.Tab> onPanel) {
        this.onPanel = onPanel;
    }

    public void insert(String text) {
        input.insertText(input.getCaretPosition(), text);
        focus();
    }

    /** Pastes into the message box, from wherever the focus is (⌘V with a chat open). */
    public void paste() {
        focus();
        input.paste();
    }

    /** A picture or files in the clipboard are sent, as dropping them would; text is left to the box. */
    private boolean pasteMedia() {
        if (chatId == null) return false;
        try {
            Pasting.Send s = Pasting.toSend(Pasting.Copied.from(javafx.scene.input.Clipboard.getSystemClipboard()), Pasting.folder());
            if (s == null) return false;
            send(s.files(), s.as(), false);
        } catch (java.io.IOException e) {
            fail(e);
        }
        return true;
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
        String chat = chatId;
        // In its turn: behind texts still being corrected before they go, so a caption sent first arrives first.
        inTurn.send(chat, () -> {
            if (files.size() == 1 && !batch) {
                svc.sendFile(chat, files.get(0).toPath(), reply, as, p -> { }).exceptionally(this::fail);
            } else {
                List<UploadQueue.Upload> uploads = new java.util.ArrayList<>();
                for (File f : files) uploads.add(new UploadQueue.Upload(chat, f.toPath(), uploads.isEmpty() ? reply : null, as));
                UploadQueue.of(svc).addAll(uploads);
            }
        });
        cancelMode();
    }

    /**
     * As in WhatsApp and Telegram: a file as it is, photos and videos (pictures made into photos the network shows as
     * such, several at once), a document (the original, never a photo), a moving picture (a video or GIF as an animated
     * WebP that plays by itself), or many files or many photos one by one.
     */
    private javafx.scene.control.ContextMenu attachMenu() {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(
                attachItem("composer.attach.file", Icons.ATTACH, () -> choose(false, SendAs.AS_IS, false)),
                attachItem("composer.attach.photo", Icons.IMAGE, () -> choose(true, SendAs.PHOTO, false)),
                attachItem("composer.attach.document", Icons.FILE, () -> choose(false, SendAs.DOCUMENT, false)),
                attachItem("composer.attach.moving", Icons.REPLAY, () -> choose(false, SendAs.MOVING, false)),
                attachItem("composer.attach.batch", Icons.UPLOAD, () -> choose(false, SendAs.AS_IS, true)),
                attachItem("composer.attach.photoBatch", Icons.CAMERA, () -> choose(true, SendAs.PHOTO, true)),
                attachItem("composer.attach.protected", Icons.SHIELD, () -> { if (onProtect != null) onProtect.run(); }));
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
                : photos ? "composer.attach.photo" : as == SendAs.MOVING ? "composer.attach.moving" : "composer.attach"));
        if (photos) fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.t("composer.attach.photos"),
                "*.jpg", "*.jpeg", "*.png", "*.gif", "*.webp", "*.heic", "*.bmp", "*.mp4", "*.mov", "*.m4v", "*.webm", "*.mkv"));
        if (as == SendAs.MOVING) fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.t("composer.attach.movingFiles"),
                "*.mp4", "*.mov", "*.m4v", "*.webm", "*.mkv", "*.gif", "*.webp", "*.tgs", "*.json"));
        javafx.stage.Window w = getScene().getWindow();
        if (batch || photos) {
            send(fc.showOpenMultipleDialog(w), as, batch);
        } else {
            File f = fc.showOpenDialog(w);
            if (f != null) send(List.of(f), as, false);
        }
    }

    /** ⌘I writes and ⌘O corrects, from anywhere in the window. True if it's one of them, with the AI buttons on. */
    public boolean aiKeyPressed(KeyCode key) { return ai.keyPressed(key); }

    public void aiKeyReleased(KeyCode key) { ai.keyReleased(key); }

    /** What the open chat's network supports; null with no chat. */
    private app.unichat.model.@Nullable RoomFeatures features() {
        Chat c = chatId == null ? null : svc.chat(chatId);
        return c == null ? null : c.features();
    }

    private void onKey(KeyEvent e) {
        if (FormatMenu.onKey(e, input, features())) return;
        if (e.getCode() == KeyCode.ENTER) {
            onEnter(e);
        } else if ((e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) && e.isShiftDown()
                && !e.isShortcutDown() && !e.isAltDown()) {
            e.consume();
            lineSelection.step(e.getCode() == KeyCode.UP);
        } else if ((e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) && e.isShiftDown() && e.isShortcutDown()
                && !e.isAltDown() && app.unichat.support.Os.mac()) {
            // ⇧⌘↑ / ⇧⌘↓: from where the selection started to the text's start / end, as macOS does. JavaFX's own
            // extended from the selection's nearer end: a second ⇧⌘↑ lost it, and after ⇧⌘↑ a ⇧⌘↓ grew it the wrong way.
            e.consume();
            input.selectRange(input.getAnchor(), e.getCode() == KeyCode.UP ? 0 : input.getLength());
        } else if (e.getCode() == KeyCode.ESCAPE && (replyTo != null || editing != null)) {
            e.consume();
            cancelMode();
        } else if (e.getCode() == KeyCode.UP && input.getText().isEmpty() && chatId != null) {
            editLastSent(e, chatId);
        }
    }

    /** Enter sends or breaks the line, as the setting says; with a modifier, the other one. */
    private void onEnter(KeyEvent e) {
        boolean sendOnEnter = Settings.get().sendOnEnter();
        boolean modifier = e.isShortcutDown();
        if ((sendOnEnter && !e.isShiftDown() && !e.isAltDown()) || (!sendOnEnter && modifier)) {
            e.consume();
            send();
        } else if (e.isShiftDown() || e.isAltDown() || modifier) {
            e.consume();
            input.insertText(input.getCaretPosition(), "\n");
        }
    }

    /** ↑ in an empty box: the last text I sent, to edit. */
    private void editLastSent(KeyEvent e, String open) {
        List<Message> msgs = svc.messages(open);
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m.outgoing() && !m.deleted() && !m.isMedia()) {
                e.consume();
                edit(m);
                return;
            }
        }
    }

    private void send() {
        String text = input.getText().strip();
        if (text.isEmpty() || chatId == null) return;
        if (editing != null) {
            if (!text.equals(editing.body())) svc.editMessage(chatId, editing.id(), text).exceptionally(this::fail);
        } else {
            // Told before it goes: the server may confirm it before the next line runs.
            String replyId = replyTo == null ? null : replyTo.id();
            if (!sending.before(chatId, text, replyId)) svc.sendText(chatId, text, replyId).exceptionally(this::fail);
            ai.sent(chatId, text);
        }
        input.clear();
        drafts.remove(chatId);
        cancelMode();
        stopTyping();
    }

    /** What happens to each text sent from the box (not an edit) just before it goes: correcting it, when that's on. */
    void setSending(Sending sending) {
        this.sending = sending;
    }

    /** What the box holds, as it's typed: stickers are suggested from it. */
    public javafx.beans.value.ObservableValue<String> typed() {
        return input.textProperty();
    }

    /** Whether a sent message is being edited: no sticker can take its place. */
    public boolean isEditing() {
        return editing != null;
    }

    /**
     * What empties the box later (a suggested sticker went instead of what was typed): only if it's still this chat's,
     * holding the same text, and not an edit; anything else typed or another chat open meanwhile stays.
     */
    public Runnable clearLater() {
        String chat = chatId;
        String text = input.getText();
        return () -> {
            if (editing == null && java.util.Objects.equals(chat, chatId) && text.equals(input.getText())) {
                input.clear();
                drafts.remove(chatId);
            }
        };
    }

    /** {@code r} runs when the chat or the box's mode (replying, editing) changes. */
    public void setOnModeChange(Runnable r) {
        onModeChange = r;
    }

    /** Puts {@code n} just above the box (under what's already there), for another package's line. */
    public void showJustAbove(Node n) {
        statuses.getChildren().add(n);
    }

    /** Files and voice notes go in their chat's turn through {@code inTurn}. */
    void setInTurn(InTurn inTurn) {
        this.inTurn = inTurn;
    }

    /** Shows {@code n} above the box, with what the AI and the voice are doing. */
    void showAbove(Node n) {
        statuses.getChildren().add(0, n);
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

    private Runnable onModeChange = () -> { };

    private void updateBanner() {
        onModeChange.run();
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

    /** The text the box draws, found after its first layout. */
    private @Nullable Text drawn;

    /** Most of the window the box may take; past it, it scrolls. */
    private static final double MAX_SHARE = 0.4;

    /**
     * As tall as the text the box draws (its own Text node: right whatever the width, size or font), up to
     * {@link #MAX_SHARE} of the window. Guessing it from the font size cut lines at large text sizes.
     */
    private void autoGrow() {
        if (drawn == null) {
            for (Node n : input.lookupAll(".text")) { // the paragraph, inside a Group; the placeholder isn't
                if (n instanceof Text t && t.getParent() instanceof javafx.scene.Group) drawn = t;
            }
            // Wrapped to a new width after the box's own layout: its size changing is what says so.
            if (drawn != null) drawn.layoutBoundsProperty().addListener((o, a, b) -> autoGrow());
        }
        if (drawn == null || !(input.lookup(".scroll-pane") instanceof ScrollPane sp) || !(sp.getContent() instanceof Region content)) {
            return; // no skin yet: the first layout comes back here
        }
        double h = drawn.getLayoutBounds().getHeight() + content.snappedTopInset() + content.snappedBottomInset()
                + sp.snappedTopInset() + sp.snappedBottomInset() + input.snappedTopInset() + input.snappedBottomInset();
        double max = getScene() == null ? 300 : Math.max(80, getScene().getHeight() * MAX_SHARE);
        input.setPrefHeight(Math.ceil(Math.min(h, max))); // the minimum is the stacked layout's (stack)
    }
}

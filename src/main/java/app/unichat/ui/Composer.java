package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
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

    public Composer(ChatService svc, Consumer<String> onError) {
        this.svc = svc;
        this.onError = onError;
        getStyleClass().add("composer");

        bannerTitle.getStyleClass().add("banner-title");
        bannerText.getStyleClass().add("banner-text");
        VBox bannerBody = new VBox(1, bannerTitle, bannerText);
        HBox.setHgrow(bannerBody, Priority.ALWAYS);
        Button close = iconButton(Icons.CLOSE, "Cancelar (Esc)", this::cancelMode);
        banner.getChildren().addAll(bannerBody, close);
        banner.getStyleClass().add("composer-banner");
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setVisible(false);
        banner.setManaged(false);

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

        Button attach = iconButton(Icons.ATTACH, "Adjuntar archivo", this::chooseFile);
        Button emoji = iconButton(Icons.EMOJI, "Emojis", null);
        emoji.setOnAction(e -> {
            if (onPanel != null) onPanel.accept(SidePanel.Tab.EMOJIS);
            else EmojiPicker.show(emoji, this::insert);
        });
        Button stickers = new Button("GIF");
        stickers.getStyleClass().addAll("icon-button", "gif-button");
        stickers.setAccessibleText("Stickers y GIFs");
        stickers.setTooltip(new javafx.scene.control.Tooltip("Stickers y GIFs"));
        stickers.setOnAction(e -> { if (onPanel != null) onPanel.accept(SidePanel.Tab.STICKERS); });
        Button send = iconButton(Icons.SEND, "Enviar", this::send);
        send.getStyleClass().add("send-button");
        send.disableProperty().bind(input.textProperty().map(t -> t.isBlank()));

        HBox row = new HBox(4, attach, input, stickers, emoji, send);
        row.setAlignment(Pos.BOTTOM_CENTER);
        row.getStyleClass().add("composer-row");
        getChildren().addAll(banner, row);

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

    static Button iconButton(String icon, String tooltip, Runnable action) {
        Button b = new Button();
        b.setGraphic(Icons.icon(icon, 18));
        b.getStyleClass().add("icon-button");
        b.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        b.setAccessibleText(tooltip);
        if (action != null) b.setOnAction(e -> action.run());
        return b;
    }

    public void setChat(String newChatId) {
        if (chatId != null) {
            stopTyping();
            drafts.put(chatId, editing == null ? input.getText() : "");
        }
        chatId = newChatId;
        replyTo = null;
        editing = null;
        updateBanner();
        input.setText(drafts.getOrDefault(chatId, ""));
        input.positionCaret(input.getLength());
    }

    public void focus() { input.requestFocus(); }

    public void reply(Message m) {
        editing = null;
        replyTo = m;
        updateBanner();
        focus();
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
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        Platform.runLater(() -> onError.accept("No se pudo enviar: " + cause.getMessage()));
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
        banner.setVisible(show);
        banner.setManaged(show);
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

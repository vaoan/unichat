package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Mirrors the bridge-bot conversation as a friendly form. */
final class LoginWizard implements ChatService.Listener {
    /** Where the wizard can take the person next. */
    interface Navigation {
        void openBotChat(String chatId);
        void showPicker();
        void showList();
        void startLogin(Network n);
        void setBack(Runnable action);
    }

    private final ChatService svc;
    private final Consumer<String> toast;
    private final Navigation nav;
    final Network network;
    final String botChatId;
    final Instant since;
    final VBox root = new VBox(14);
    final Label instructions = new Label();
    final StackPane qrBox = new StackPane();
    final ImageView qr = new ImageView();
    final Label botText = new Label();
    final TextArea input = new TextArea();
    final Button send = new Button("Enviar");
    final HBox inputRow;
    final VBox done = new VBox(12);
    String shownQr;
    boolean finished;
    boolean sent;
    Instant sentAt = Instant.EPOCH;
    /** True while the cookie-source chooser is on screen: bot messages must not replace it. */
    boolean chooserActive;

    LoginWizard(ChatService svc, Consumer<String> toast, Navigation nav, Network network, String botChatId, Instant since) {
        this.svc = svc;
        this.toast = toast;
        this.nav = nav;
        this.network = network;
        this.botChatId = botChatId;
        this.since = since;
        instructions.setText(LoginTexts.instructions(network));
        instructions.setWrapText(true);
        instructions.setMinHeight(Region.USE_PREF_SIZE);
        instructions.getStyleClass().add("wizard-instructions");

        qr.setFitWidth(260);
        qr.setFitHeight(260);
        qr.setPreserveRatio(true);
        qr.setSmooth(false); // keep QR modules crisp
        qrBox.getChildren().add(qr);
        qrBox.getStyleClass().add("qr-box");
        qrBox.setMaxSize(284, 284);
        Nodes.show(qrBox, false);
        qrBox.setAccessibleText("Código QR para vincular " + network.displayName());

        botText.setWrapText(true);
        botText.setMinHeight(Region.USE_PREF_SIZE);
        botText.getStyleClass().add("wizard-bot");

        input.setPromptText(LoginTexts.promptFor(network));
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.setAccessibleText(LoginTexts.promptFor(network));
        HBox.setHgrow(input, Priority.ALWAYS);
        send.getStyleClass().add("primary-button");
        send.setOnAction(e -> submit());
        send.disableProperty().bind(input.textProperty().map(String::isBlank));
        input.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER && e.isShortcutDown()) submit();
        });
        inputRow = new HBox(8, input, send);
        inputRow.setAlignment(Pos.BOTTOM_LEFT);
        boolean needsInput = !LoginTexts.qrNetwork(network);
        Nodes.show(inputRow, needsInput);

        Button details = new Button("Ver el chat técnico del puente");
        details.getStyleClass().add("link-button");
        details.setOnAction(e -> {
            nav.openBotChat(botChatId);
        });
        Nodes.show(done, false);
        done.setAlignment(Pos.CENTER);

        root.getChildren().addAll(instructions, qrBox, botText, inputRow, done, details);
        root.setAlignment(Pos.TOP_CENTER);
        svc.addListener(this);
        update();
    }

    /** A session was sent to the bridge: hide the manual-paste UI and wait for its answer. */
    void sessionSent() {
        sent = true;
        chooserActive = false;
        sentAt = Instant.now();
        instructions.setText("Enviamos tu sesión al puente. Esperando su confirmación…");
        Nodes.show(inputRow, false);
    }

    /** Sending the session failed: back to the manual state. */
    void sessionFailed() {
        sent = false;
        chooserActive = true;
        instructions.setText(LoginTexts.instructions(network));
        Nodes.show(inputRow, true);
    }

    void dispose() {
        svc.removeListener(this);
    }

    void cancel() {
        dispose();
        if (!finished) svc.cancelLogin(network, botChatId);
    }

    @Override
    public void messagesChanged(String chatId) {
        if (botChatId.equals(chatId)) Platform.runLater(this::update);
    }

    private void submit() {
        String text = input.getText().strip();
        if (text.isEmpty()) return;
        input.clear();
        svc.sendText(botChatId, text, null).exceptionally(err -> {
            Platform.runLater(() -> toast.accept("No se pudo enviar: " + LoginTexts.rootMessage(err)));
            return null;
        });
    }

    /** Renders the newest bot state: QR image, last instruction text, success or failure. */
    void update() {
        if (finished || chooserActive) return;
        List<Message> msgs = svc.messages(botChatId).stream()
                .filter(m -> !m.outgoing() && !m.timestamp().isBefore(since)).toList();
        Message lastImage = null;
        Message lastText = null;
        for (Message m : msgs) {
            if (m.deleted()) continue;
            if (m.type() == MessageType.IMAGE && m.mediaUrl() != null) lastImage = m;
            else if (!m.body().isBlank()) lastText = m;
        }
        if (lastText != null) {
            String b = lastText.body().toLowerCase(Locale.ROOT);
            if (b.contains("successfully logged in") || b.contains("sesión iniciada")) {
                success(lastText.body());
                return;
            }
            if (b.contains("login failed") || b.contains("login cancelled") || b.contains("invalid")
                    || b.contains("timed out")) {
                failure(lastText.body());
                return;
            }
            botText.setText("Puente: " + lastText.body());
            boolean asksInput = !LoginTexts.qrNetwork(network) || b.contains("password") || b.contains("contraseña")
                    || ((b.contains("code") || b.contains("código")) && !b.contains("qr"));
            // after a session was sent, only a prompt newer than that send re-opens the input
            boolean showInput = asksInput && (!sent || lastText.timestamp().isAfter(sentAt));
            Nodes.show(inputRow, showInput);
        }
        if (lastImage != null && !lastImage.mediaUrl().equals(shownQr)) {
            String url = lastImage.mediaUrl();
            shownQr = url;
            svc.media(url, 0, 0).thenAccept(p -> Platform.runLater(() -> {
                if (!url.equals(shownQr)) return;
                qr.setImage(new Image(p.toUri().toString()));
                Nodes.show(qrBox, true);
            }));
        }
    }

    private void success(String botMessage) {
        finished = true;
        dispose();
        Label ok = new Label("✅");
        ok.getStyleClass().add("jumbo-emoji");
        Label head = new Label(network.displayName() + " conectado");
        head.getStyleClass().add("sheet-title");
        Label sub = Labels.help(botMessage.replaceAll("(?i)successfully logged in as", "Sesión iniciada como")
                + "\nTus chats van a ir apareciendo en la bandeja en los próximos minutos.");
        Button another = new Button("Agregar otra cuenta");
        another.getStyleClass().add("secondary-button");
        another.setOnAction(e -> nav.showPicker());
        Button finish = new Button("Listo");
        finish.getStyleClass().add("primary-button");
        finish.setDefaultButton(true);
        finish.setOnAction(e -> nav.showList());
        HBox buttons = new HBox(10, another, finish);
        buttons.setAlignment(Pos.CENTER);
        done.getChildren().setAll(ok, head, sub, buttons);
        show(done);
        nav.setBack(nav::showList);
        Platform.runLater(finish::requestFocus);
    }

    private void failure(String botMessage) {
        finished = true;
        dispose();
        Label head = new Label("No se pudo conectar " + network.displayName());
        head.getStyleClass().add("sheet-title");
        Label sub = Labels.help("El puente respondió: " + botMessage);
        Button retry = new Button("Intentar de nuevo");
        retry.getStyleClass().add("primary-button");
        retry.setOnAction(e -> nav.startLogin(network));
        done.getChildren().setAll(head, sub, retry);
        show(done);
        nav.setBack(nav::showPicker);
    }

    private void show(Node only) {
        for (Node n : root.getChildren()) {
            boolean keep = n == only;
            Nodes.show(n, keep);
        }
    }
}

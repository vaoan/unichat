package app.unichat.ui;

import app.unichat.login.BrowserSession;
import app.unichat.login.CookieLogin;
import app.unichat.login.CookieSpec;
import app.unichat.login.SessionCookies;
import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The "Cuentas" sheet: every connected account one per row, "Agregar cuenta" → pick a network (one per row,
 * any number of accounts per network) → a login wizard that drives the bridge bot behind the scenes and shows
 * its QR code, asks for cookies/e-mail/codes, and confirms when the account is connected.
 */
public final class AccountsPane extends VBox {
    private final ChatService svc;
    private final Runnable close;
    private final Consumer<String> toast;
    private final Consumer<String> openChatId;
    private final StackPane body = new StackPane();
    private final Label title = new Label();
    private final Button back;
    private Runnable onBack;
    private Wizard wizard;

    /** {@code close} null = embedded in the settings window (no close button, no card frame). */
    public AccountsPane(ChatService svc, Runnable close, Consumer<String> toast, Consumer<String> openChatId) {
        this.svc = svc;
        this.close = close;
        this.toast = toast;
        this.openChatId = openChatId;
        getStyleClass().add("accounts-sheet");
        if (close != null) {
            getStyleClass().add("card");
            setMaxSize(640, 640);
        }
        setSpacing(14);

        title.getStyleClass().add("sheet-title");
        back = Composer.iconButton(Icons.BACK, "Volver", () -> { if (onBack != null) onBack.run(); });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox head = new HBox(6, back, title, grow);
        if (close != null) head.getChildren().add(Composer.iconButton(Icons.CLOSE, "Cerrar", this::closeSheet));
        head.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(head, body);
        showList();
    }

    /** Stops listening to the service; called when the sheet closes. */
    public void dispose() {
        if (wizard != null) wizard.dispose();
        wizard = null;
    }

    private void closeSheet() {
        dispose();
        if (close != null) close.run();
    }

    /** Re-reads accounts if the list is showing (accountsChanged). The wizard updates itself. */
    public void refresh() {
        if (wizard == null && onBack == null) showList();
    }

    private void setView(String heading, Node content, Runnable backAction) {
        title.setText(heading);
        onBack = backAction;
        back.setVisible(backAction != null);
        back.setManaged(backAction != null);
        body.getChildren().setAll(content);
    }

    // ------------------------------------------------------------------ 1. accounts list

    public void showList() {
        dispose();
        VBox rows = new VBox(0);
        rows.getStyleClass().add("settings-group");
        List<Account> accounts = svc.accounts().stream().filter(a -> a.status() != AccountStatus.NEEDS_LOGIN)
                .sorted(java.util.Comparator.comparing((Account a) -> a.network().ordinal()).thenComparing(Accounts::name))
                .toList();
        if (accounts.isEmpty()) {
            Label empty = new Label("Aún no tienes cuentas conectadas. Agrega la primera: puedes conectar varias de cada red.");
            empty.setWrapText(true);
            empty.getStyleClass().add("settings-help");
            HBox row = new HBox(empty);
            row.getStyleClass().add("settings-row");
            rows.getChildren().add(row);
        }
        for (Account a : accounts) rows.getChildren().add(accountRow(a));
        Button add = new Button("Agregar cuenta");
        add.setGraphic(Icons.icon(Icons.PLUS, 16));
        add.getStyleClass().add("primary-button");
        add.setOnAction(e -> showPicker());
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Label sub = new Label(accounts.size() == 1 ? "1 cuenta conectada" : accounts.size() + " cuentas conectadas");
        sub.getStyleClass().add("sheet-subtitle");
        VBox content = new VBox(12, sub, scroll, add);
        setView("Cuentas", content, null);
        Platform.runLater(add::requestFocus);
    }

    private Node accountRow(Account a) {
        Label icon = networkIcon(a.network(), 34);
        Label name = new Label(Accounts.name(a));
        name.getStyleClass().add("settings-label");
        Label detail = new Label(a.network().displayName() + (Accounts.name(a).equals(a.label()) ? "" : " · " + a.label()));
        detail.getStyleClass().add("settings-help");
        VBox text = new VBox(2, name, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label status = statusPill(a.status());
        Button more = Composer.iconButton(Icons.MORE, "Opciones de " + Accounts.name(a), null);
        more.setOnAction(e -> accountMenu(a).show(more, Side.BOTTOM, 0, 4));
        HBox row = new HBox(12, icon, text, status, more);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        row.setAccessibleText(Accounts.name(a) + ", " + a.network().displayName() + ", " + status.getText());
        return row;
    }

    private ContextMenu accountMenu(Account a) {
        ContextMenu m = new ContextMenu(
                ChatMenus.item("Cambiar nombre…", () -> showRename(a)),
                ChatMenus.item("Agregar otra cuenta de " + a.network().displayName(), () -> startLogin(a.network())),
                new SeparatorMenuItem(),
                ChatMenus.danger(ChatMenus.item("Desconectar…", () -> showDisconnect(a))));
        return m;
    }

    /** Disconnecting logs the account out on the bridge, so it always asks first. */
    private void showDisconnect(Account a) {
        Label icon = networkIcon(a.network(), 48);
        Label q = new Label("¿Desconectar " + a.network().displayName() + " · " + Accounts.name(a) + "?");
        q.getStyleClass().add("sheet-title");
        q.setWrapText(true);
        Label info = new Label("Se cierra la sesión en el puente y dejarás de recibir mensajes de esta cuenta. "
                + "Tus chats ya sincronizados se quedan. Para volver a conectarla tendrás que iniciar sesión de nuevo"
                + (qrNetwork(a.network()) ? " (escanear el QR)." : "."));
        info.setWrapText(true);
        info.getStyleClass().add("settings-help");
        Button cancel = new Button("Cancelar");
        cancel.getStyleClass().add("secondary-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> showList());
        Button confirm = new Button("Desconectar");
        confirm.getStyleClass().add("danger-button");
        confirm.setOnAction(e -> {
            confirm.setDisable(true);
            svc.disconnectAccount(a).whenComplete((v, err) -> Platform.runLater(() -> {
                toast.accept(err == null ? Accounts.name(a) + " desconectada" : "No se pudo desconectar: " + rootMessage(err));
                showList();
            }));
        });
        HBox buttons = new HBox(10, cancel, confirm);
        VBox content = new VBox(14, icon, q, info, buttons);
        setView("Desconectar cuenta", content, this::showList);
        Platform.runLater(cancel::requestFocus);
    }

    private void showRename(Account a) {
        TextField field = new TextField(Settings.get().accountAlias(a.id()) == null ? "" : Settings.get().accountAlias(a.id()));
        field.setPromptText("Ej. Personal, Trabajo");
        field.setAccessibleText("Nombre de la cuenta");
        Label hint = new Label("Solo se usa en Unichat para distinguir tus cuentas de " + a.network().displayName()
                + ". Déjalo vacío para usar " + a.label() + ".");
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-help");
        Button save = new Button("Guardar");
        save.getStyleClass().add("primary-button");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            Settings.get().setAccountAlias(a.id(), field.getText());
            showList();
        });
        VBox content = new VBox(12, field, hint, save);
        setView("Nombre de la cuenta", content, this::showList);
        Platform.runLater(field::requestFocus);
    }

    // ------------------------------------------------------------------ 2. network picker

    public void showPicker() {
        dispose();
        VBox rows = new VBox(0);
        rows.getStyleClass().add("settings-group");
        List<Network> available = svc.availableNetworks();
        for (Network n : Network.values()) {
            if (!n.isBridged()) continue;
            boolean ok = available.contains(n);
            int count = Accounts.of(svc, n).size();
            Label icon = networkIcon(n, 34);
            Label name = new Label(n.displayName());
            name.getStyleClass().add("settings-label");
            Label method = new Label(ok ? method(n) : "Puente no instalado en tu servidor");
            method.getStyleClass().add("settings-help");
            VBox text = new VBox(2, name, method);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(12, icon, text);
            if (count > 0) {
                Label c = new Label(count == 1 ? "1 conectada" : count + " conectadas");
                c.getStyleClass().addAll("status-pill", "ok");
                c.setMinWidth(Region.USE_PREF_SIZE);
                row.getChildren().add(c);
            }
            row.getChildren().add(Icons.icon(Icons.BACK, 14));
            row.getChildren().get(row.getChildren().size() - 1).setRotate(180);
            row.setAlignment(Pos.CENTER_LEFT);
            Button b = new Button();
            b.setGraphic(row);
            b.setMaxWidth(Double.MAX_VALUE);
            row.prefWidthProperty().bind(b.widthProperty().subtract(24));
            b.getStyleClass().add("picker-row");
            b.setDisable(!ok);
            b.setAccessibleText(n.displayName() + ", " + method.getText() + (count > 0 ? ", " + count + " conectadas" : ""));
            b.setOnAction(e -> startLogin(n));
            rows.getChildren().add(b);
        }
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Label sub = new Label("Elige la red. Puedes agregar tantas cuentas de cada una como quieras.");
        sub.setWrapText(true);
        sub.getStyleClass().add("sheet-subtitle");
        setView("Agregar cuenta", new VBox(12, sub, scroll), this::showList);
    }

    static String method(Network n) {
        return switch (n) {
            case WHATSAPP, SIGNAL, TELEGRAM, DISCORD -> "Escanea un código QR con tu teléfono";
            case INSTAGRAM, MESSENGER, X, LINKEDIN -> "Usa tu sesión del navegador o inicia sesión aquí";
            case GOOGLE_MESSAGES -> "Tu sesión de Google + confirmación en el teléfono";
            case SLACK -> "Tu correo y el código que te llega";
            default -> "";
        };
    }

    /** What to do on the phone / in the browser, in Spanish. */
    static String instructions(Network n) {
        return switch (n) {
            case WHATSAPP -> "En tu teléfono abre WhatsApp → Ajustes → Dispositivos vinculados → Vincular un dispositivo, y escanea el código.";
            case SIGNAL -> "En tu teléfono abre Signal → Ajustes → Dispositivos vinculados → +, y escanea el código.";
            case TELEGRAM -> "En tu teléfono abre Telegram → Ajustes → Dispositivos → Vincular dispositivo de escritorio, y escanea el código.";
            case DISCORD -> "En la app de Discord del teléfono toca tu perfil → Escanear código QR.";
            case INSTAGRAM, MESSENGER, X, LINKEDIN -> "1. Abre " + site(n) + " en tu navegador con tu sesión iniciada.\n"
                    + "2. Abre las herramientas de desarrollador (F12) → pestaña Red, y recarga la página.\n"
                    + "3. Clic derecho en cualquier petición a " + site(n) + " → Copiar → Copiar como cURL.\n"
                    + "4. Pega el texto abajo y pulsa Enviar.";
            case GOOGLE_MESSAGES -> "1. Abre messages.google.com en tu navegador con tu cuenta de Google.\n"
                    + "2. F12 → Red → recarga → clic derecho en una petición → Copiar como cURL, pégalo abajo.\n"
                    + "3. Después confirma el emoji que aparece en tu teléfono.";
            case SLACK -> "Escribe abajo el correo de tu cuenta de Slack. Luego te llegará un código: pégalo aquí también.";
            default -> "Sigue las indicaciones del puente.";
        };
    }

    private static String site(Network n) {
        return switch (n) {
            case INSTAGRAM -> "instagram.com";
            case MESSENGER -> "facebook.com";
            case X -> "x.com";
            case LINKEDIN -> "linkedin.com";
            default -> n.displayName();
        };
    }

    // ------------------------------------------------------------------ 3. login wizard

    /** Opens the wizard for {@code n} directly (rail click on an unconnected network). */
    public void startLogin(Network n) {
        dispose();
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(36, 36);
        Label wait = new Label("Preparando el inicio de sesión de " + n.displayName() + "…");
        wait.getStyleClass().add("settings-help");
        VBox loading = new VBox(14, spin, wait);
        loading.setAlignment(Pos.CENTER);
        setView("Conectar " + n.displayName(), loading, this::showPicker);
        Instant since = Instant.now().minusSeconds(2);
        svc.addAccount(n).whenComplete((botChatId, err) -> Platform.runLater(() -> {
            if (err != null) {
                wait.setText("No se pudo iniciar: " + rootMessage(err));
                spin.setVisible(false);
                return;
            }
            wizard = new Wizard(n, botChatId, since);
            Runnable cancel = () -> {
                wizard.cancel();
                showPicker();
            };
            if (CookieLogin.isCookieNetwork(n)) {
                showCookieSources(n, botChatId, cancel);
            } else {
                setView("Conectar " + n.displayName(), wizard.root, cancel);
            }
        }));
    }

    // ------------------------------------------------------------------ 3b. cookie-source chooser

    /**
     * Friendly login for cookie networks: reuse a browser session, log in inside the app, or load a cookies.txt.
     * Cookies are read only for {@code n}, sent only to the user's own bridge bot, and never logged.
     */
    private void showCookieSources(Network n, String botChatId, Runnable cancel) {
        Wizard w = wizard;
        String net = n.displayName();
        Label searching = new Label("Buscando tu sesión de " + net + " en tus navegadores…");
        searching.setWrapText(true);
        searching.getStyleClass().add("settings-help");
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(22, 22);
        HBox searchRow = new HBox(10, spin, searching);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        VBox found = new VBox(10);
        VBox top = new VBox(8, searchRow);
        if (macNotice()) {
            Label notice = new Label("macOS puede pedirte permiso para usar tu sesión del navegador.");
            notice.setWrapText(true);
            notice.getStyleClass().add("settings-help");
            top.getChildren().add(notice);
        }

        Label status = new Label();
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.getStyleClass().add("settings-help");
        status.setVisible(false);
        status.setManaged(false);

        ScrollPane[] chooser = new ScrollPane[1];
        Consumer<SessionCookies> sendSession = cookies -> {
            setView("Conectar " + net, w.root, cancel);
            w.sessionSent();
            svc.sendLoginInput(n, botChatId, cookies.toBridgeJson()).exceptionally(err -> {
                Platform.runLater(() -> {
                    toast.accept("No se pudo enviar: " + rootMessage(err));
                    // undo the waiting state so the user can retry from the chooser
                    if (wizard == w && !w.finished) {
                        w.sessionFailed();
                        setView("Conectar " + net, chooser[0], cancel);
                    }
                });
                return null;
            });
        };

        Label heading = new Label("Elige cómo conectar tu cuenta de " + net);
        heading.setWrapText(true);
        heading.getStyleClass().add("wizard-instructions");

        Button other = new Button("Iniciar sesión con otra cuenta");
        other.getStyleClass().add("secondary-button");
        other.setMaxWidth(Double.MAX_VALUE);
        other.setOnAction(e -> {
            if (LoginWebView.isOpen()) return;
            LoginWebView.open(getScene().getWindow(), n, CookieSpec.forNetwork(n), sendSession, () -> { });
        });
        other.setDisable(LoginWebView.isOpen());

        Button file = new Button("Elegir archivo cookies.txt…");
        file.getStyleClass().add("secondary-button");
        file.setMaxWidth(Double.MAX_VALUE);
        file.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Elige tu archivo cookies.txt");
            fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("cookies.txt", "*.txt"),
                    new FileChooser.ExtensionFilter("Todos los archivos", "*.*"));
            File f = fc.showOpenDialog(getScene().getWindow());
            if (f == null) return;
            CookieLogin.TxtResult r = CookieLogin.fromCookieTxt(f.toPath(), n);
            switch (r.kind()) {
                case OK -> sendSession.accept(r.cookies());
                case MISSING -> showStatus(status, missingMessage(n, r.missing()));
                default -> showStatus(status, r.message());
            }
        });

        Button paste = new Button("Pegar cookies manualmente");
        paste.getStyleClass().add("link-button");
        paste.setOnAction(e -> setView("Conectar " + net, w.root, cancel));

        VBox content = new VBox(12, heading, top, found, other, file, status, paste);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");
        chooser[0] = scroll;
        setView("Conectar " + net, scroll, cancel);

        CookieLogin.findBrowserSessions(n).whenComplete((sessions, err) -> Platform.runLater(() -> {
            if (wizard != w) return; // user left the screen meanwhile
            top.getChildren().remove(searchRow);
            List<BrowserSession> list = err != null || sessions == null ? List.of() : sessions;
            if (list.isEmpty()) {
                Label none = new Label("No encontramos una sesión en tus navegadores.");
                none.setWrapText(true);
                none.getStyleClass().add("settings-help");
                found.getChildren().add(none);
            }
            for (BrowserSession s : list) {
                String label = sessionLabel(net, s);
                Label l = new Label(label);
                l.setWrapText(true);
                l.getStyleClass().add("settings-label");
                HBox.setHgrow(l, Priority.ALWAYS);
                l.setMaxWidth(Double.MAX_VALUE);
                Button use = new Button("Usar esta sesión");
                use.getStyleClass().add("primary-button");
                use.setAccessibleText(label);
                use.setOnAction(e -> sendSession.accept(s.cookies()));
                HBox card = new HBox(12, l, use);
                card.setAlignment(Pos.CENTER_LEFT);
                card.getStyleClass().add("settings-row");
                found.getChildren().add(card);
            }
        }));
    }

    private static void showStatus(Label status, String text) {
        status.setText(text);
        status.setVisible(true);
        status.setManaged(true);
    }

    private static boolean macNotice() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /** Card caption for a browser session, e.g. "Usar tu sesión de Instagram de Firefox (default)". */
    static String sessionLabel(String net, BrowserSession s) {
        String profile = s.profile() == null || s.profile().isBlank() ? "" : " (" + s.profile() + ")";
        return "Usar tu sesión de " + net + " de " + s.browser() + profile;
    }

    /** Message shown when a cookies.txt lacks required cookies. */
    static String missingMessage(Network n, List<String> missing) {
        return "Al archivo le falta: " + String.join(", ", missing)
                + ". Exporta las cookies con la sesión de " + n.displayName() + " abierta.";
    }

    /** Mirrors the bridge-bot conversation as a friendly form. */
    private final class Wizard implements ChatService.Listener {
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

        Wizard(Network network, String botChatId, Instant since) {
            this.network = network;
            this.botChatId = botChatId;
            this.since = since;
            instructions.setText(instructions(network));
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
            qrBox.setVisible(false);
            qrBox.setManaged(false);
            qrBox.setAccessibleText("Código QR para vincular " + network.displayName());

            botText.setWrapText(true);
            botText.setMinHeight(Region.USE_PREF_SIZE);
            botText.getStyleClass().add("wizard-bot");

            input.setPromptText(promptFor(network));
            input.setWrapText(true);
            input.setPrefRowCount(3);
            input.setAccessibleText(promptFor(network));
            HBox.setHgrow(input, Priority.ALWAYS);
            send.getStyleClass().add("primary-button");
            send.setOnAction(e -> submit());
            send.disableProperty().bind(input.textProperty().map(String::isBlank));
            input.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER && e.isShortcutDown()) submit();
            });
            inputRow = new HBox(8, input, send);
            inputRow.setAlignment(Pos.BOTTOM_LEFT);
            boolean needsInput = !qrNetwork(network);
            inputRow.setVisible(needsInput);
            inputRow.setManaged(needsInput);

            Button details = new Button("Ver el chat técnico del puente");
            details.getStyleClass().add("link-button");
            details.setOnAction(e -> {
                closeSheet();
                openChatId.accept(botChatId);
            });
            done.setVisible(false);
            done.setManaged(false);
            done.setAlignment(Pos.CENTER);

            root.getChildren().addAll(instructions, qrBox, botText, inputRow, done, details);
            root.setAlignment(Pos.TOP_CENTER);
            svc.addListener(this);
            update();
        }

        /** A session was sent to the bridge: hide the manual-paste UI and wait for its answer. */
        void sessionSent() {
            sent = true;
            instructions.setText("Enviamos tu sesión al puente. Esperando su confirmación…");
            inputRow.setVisible(false);
            inputRow.setManaged(false);
        }

        /** Sending the session failed: back to the manual state. */
        void sessionFailed() {
            sent = false;
            instructions.setText(instructions(network));
            inputRow.setVisible(true);
            inputRow.setManaged(true);
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
            svc.sendLoginInput(network, botChatId, text).exceptionally(err -> {
                Platform.runLater(() -> toast.accept("No se pudo enviar: " + rootMessage(err)));
                return null;
            });
        }

        /** Renders the newest bot state: QR image, last instruction text, success or failure. */
        void update() {
            if (finished) return;
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
                boolean asksInput = !qrNetwork(network) || b.contains("password") || b.contains("contraseña")
                        || ((b.contains("code") || b.contains("código")) && !b.contains("qr"));
                boolean showInput = asksInput && !sent;
                inputRow.setVisible(showInput);
                inputRow.setManaged(showInput);
            }
            if (lastImage != null && !lastImage.mediaUrl().equals(shownQr)) {
                String url = lastImage.mediaUrl();
                shownQr = url;
                svc.media(url, 0, 0).thenAccept(p -> Platform.runLater(() -> {
                    if (!url.equals(shownQr)) return;
                    qr.setImage(new Image(p.toUri().toString()));
                    qrBox.setVisible(true);
                    qrBox.setManaged(true);
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
            Label sub = new Label(botMessage.replaceAll("(?i)successfully logged in as", "Sesión iniciada como")
                    + "\nTus chats van a ir apareciendo en la bandeja en los próximos minutos.");
            sub.setWrapText(true);
            sub.getStyleClass().add("settings-help");
            Button another = new Button("Agregar otra cuenta");
            another.getStyleClass().add("secondary-button");
            another.setOnAction(e -> showPicker());
            Button finish = new Button("Listo");
            finish.getStyleClass().add("primary-button");
            finish.setDefaultButton(true);
            finish.setOnAction(e -> showList());
            HBox buttons = new HBox(10, another, finish);
            buttons.setAlignment(Pos.CENTER);
            done.getChildren().setAll(ok, head, sub, buttons);
            show(done);
            onBack = AccountsPane.this::showList;
            Platform.runLater(finish::requestFocus);
        }

        private void failure(String botMessage) {
            finished = true;
            dispose();
            Label head = new Label("No se pudo conectar " + network.displayName());
            head.getStyleClass().add("sheet-title");
            Label sub = new Label("El puente respondió: " + botMessage);
            sub.setWrapText(true);
            sub.getStyleClass().add("settings-help");
            Button retry = new Button("Intentar de nuevo");
            retry.getStyleClass().add("primary-button");
            retry.setOnAction(e -> startLogin(network));
            done.getChildren().setAll(head, sub, retry);
            show(done);
            onBack = AccountsPane.this::showPicker;
        }

        private void show(Node only) {
            for (Node n : root.getChildren()) {
                boolean keep = n == only;
                n.setVisible(keep);
                n.setManaged(keep);
            }
        }
    }

    static boolean qrNetwork(Network n) {
        return switch (n) {
            case WHATSAPP, SIGNAL, TELEGRAM, DISCORD -> true;
            default -> false;
        };
    }

    private static String promptFor(Network n) {
        return switch (n) {
            case SLACK -> "Correo o código de verificación";
            case INSTAGRAM, MESSENGER, X, LINKEDIN, GOOGLE_MESSAGES -> "Pega aquí el cURL o las cookies";
            default -> "Código o contraseña que pida el puente";
        };
    }

    static Label networkIcon(Network n, double size) {
        Label l = new Label(n.badgeLetter());
        l.getStyleClass().add("network-tile-icon");
        l.setStyle("-fx-background-color: " + n.color() + "; -fx-min-width: " + size + "; -fx-min-height: " + size
                + "; -fx-max-width: " + size + "; -fx-max-height: " + size + ";");
        return l;
    }

    private static Label statusPill(AccountStatus s) {
        Label l = new Label(switch (s) {
            case CONNECTED -> "Conectada";
            case CONNECTING -> "Conectando…";
            case NEEDS_LOGIN -> "Inicia sesión";
            case ERROR -> "Error";
            case NOT_AVAILABLE -> "No disponible";
        });
        l.getStyleClass().addAll("status-pill", s == AccountStatus.CONNECTED ? "ok" : "warn");
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}

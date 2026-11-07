package app.unichat.ui;

import app.unichat.login.BridgeCookies;
import app.unichat.login.ChromeLogin;
import app.unichat.settings.Settings;
import app.unichat.login.BridgeLogin;
import app.unichat.login.BrowserSession;
import app.unichat.login.CookieLogin;
import app.unichat.login.CookieSpec;
import app.unichat.login.LoginCopy;
import app.unichat.login.SessionCookies;
import app.unichat.model.Network;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Dynamic login: asks the network's bridge which login methods and steps it has (BridgeLogin) and shows each step
 * with its own screen (QR, code, emoji, form, cookies) and Spanish copy (LoginCopy). Nothing per network is
 * hardcoded here, so new or changed bridge login methods show up by themselves.
 */
final class BridgeLoginPane extends StackPane {
    private final Network network;
    private final BridgeLogin api;
    private final Supplier<Window> owner;
    private final Runnable onConnected;
    private final Runnable onUseBotChat;
    private volatile boolean closed;
    private volatile ChromeLogin chromeLogin;
    private volatile BridgeLogin.Step current;
    private String lastStepId;
    private int stepNumber;

    /**
     * @param onConnected  the login finished ("Listo")
     * @param onUseBotChat the bridge asked for something Unichat can't collect: fall back to the bot chat
     */
    BridgeLoginPane(Network network, BridgeLogin api, Supplier<Window> owner, Runnable onConnected, Runnable onUseBotChat) {
        this.network = network;
        this.api = api;
        this.owner = owner;
        this.onConnected = onConnected;
        this.onUseBotChat = onUseBotChat;
        getStyleClass().add("bridge-login");
        loadFlows();
    }

    /** Leaving the screen: stop waiting and cancel the login on the bridge. */
    void dispose() {
        closed = true;
        ChromeLogin chrome = chromeLogin;
        if (chrome != null) chrome.cancel();
        BridgeLogin.Step s = current;
        if (s != null && !s.isComplete()) Thread.ofVirtual().start(() -> api.cancel(s));
    }

    // ---------------------------------------------------------------- choosing a method

    private void loadFlows() {
        show(waiting("Buscando las formas de conectar " + network.displayName() + "…"));
        background(() -> {
            List<LoginCopy.Choice> choices = LoginCopy.choices(network, api.flows());
            Platform.runLater(() -> {
                if (closed) return;
                if (choices.isEmpty()) error("Este puente no ofrece ninguna forma de iniciar sesión.", null);
                else if (choices.size() == 1) begin(choices.get(0).flow()); // nothing to choose: straight in
                else showChoices(choices);
            });
        });
    }

    private void showChoices(List<LoginCopy.Choice> choices) {
        Label heading = new Label("¿Cómo quieres conectar " + network.displayName() + "?");
        heading.getStyleClass().add("wizard-instructions");
        heading.setWrapText(true);
        VBox list = new VBox(8, heading);
        VBox more = new VBox(8);
        for (LoginCopy.Choice c : choices) (c.advanced() ? more : list).getChildren().add(choiceCard(c));
        if (!more.getChildren().isEmpty()) {
            Nodes.show(more, false);
            Button toggle = new Button("Más opciones");
            toggle.getStyleClass().add("link-button");
            toggle.setOnAction(e -> {
                boolean open = !more.isVisible();
                Nodes.show(more, open);
                toggle.setText(open ? "Menos opciones" : "Más opciones");
            });
            list.getChildren().addAll(toggle, more);
        }
        show(scroll(list));
    }

    /**
     * A whole-width card that wraps its text (a Button's graphic can't follow the width): focusable, announced as a
     * button, and activated by click, Enter or Space.
     */
    private HBox choiceCard(LoginCopy.Choice c) {
        Label title = new Label(c.title());
        title.getStyleClass().add("settings-label");
        title.setWrapText(true);
        Label sub = Labels.help(c.subtitle() == null ? "" : c.subtitle());
        VBox text = new VBox(2, title, sub);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        Node arrow = Icons.icon(Icons.BACK, 14);
        arrow.setRotate(180);
        HBox card = new HBox(12, text, arrow);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().addAll("picker-row", "login-choice");
        card.setFocusTraversable(true);
        card.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        card.setAccessibleText(c.title() + ". " + (c.subtitle() == null ? "" : c.subtitle()));
        card.setCursor(javafx.scene.Cursor.HAND);
        card.setOnMouseClicked(e -> begin(c.flow()));
        card.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                e.consume();
                begin(c.flow());
            }
        });
        return card;
    }

    private void begin(BridgeLogin.Flow flow) {
        show(waiting("Preparando…"));
        stepNumber = 0;
        lastStepId = null;
        background(() -> {
            BridgeLogin.Step s = api.start(flow.id());
            Platform.runLater(() -> render(s));
        });
    }

    // ---------------------------------------------------------------- steps

    private void render(BridgeLogin.Step s) {
        if (closed) return;
        current = s;
        if (!s.stepId().equals(lastStepId)) stepNumber++; // a refreshed QR is the same step
        lastStepId = s.stepId();
        LoginCopy.StepText text = LoginCopy.step(network, s);
        if (s.isComplete()) {
            showComplete(text);
            return;
        }
        Node body = switch (s.type()) {
            case "display_and_wait" -> displayAndWait(s);
            case "user_input" -> form(s);
            case "cookies" -> cookies(s);
            default -> unsupported();
        };
        Label step = new Label("Paso " + stepNumber);
        step.getStyleClass().add("settings-help");
        Label title = new Label(text.title());
        title.getStyleClass().add("wizard-instructions");
        title.setWrapText(true);
        VBox box = new VBox(10, step, title);
        if (!text.steps().isEmpty()) box.getChildren().add(numbered(text.steps()));
        box.getChildren().add(body);
        show(scroll(box));
    }

    private Node displayAndWait(BridgeLogin.Step s) {
        BridgeLogin.Display d = s.display();
        Node shown;
        if (d != null && "qr".equals(d.type()) && d.data() != null) {
            ImageView qr = new ImageView(qr(d.data(), 260));
            qr.setAccessibleText("Código QR para vincular " + network.displayName());
            StackPane boxed = new StackPane(qr);
            boxed.getStyleClass().add("qr-box");
            boxed.setMaxWidth(Region.USE_PREF_SIZE);
            Label renew = new Label("El código se renueva solo si vence.");
            renew.getStyleClass().add("settings-help");
            shown = new VBox(8, boxed, renew);
        } else if (d != null && ("code".equals(d.type()) || "emoji".equals(d.type())) && d.data() != null) {
            Label code = new Label(d.data());
            code.getStyleClass().add("emoji".equals(d.type()) ? "login-emoji" : "login-code");
            code.setAccessibleText(("emoji".equals(d.type()) ? "Emoji: " : "Código: ") + d.data());
            HBox row = new HBox(12, code);
            row.setAlignment(Pos.CENTER_LEFT);
            if ("code".equals(d.type())) {
                Button copy = new Button("Copiar");
                copy.getStyleClass().add("secondary-button");
                copy.setOnAction(e -> {
                    Clipboards.copy(d.data());
                    copy.setText("Copiado");
                });
                row.getChildren().add(copy);
            }
            shown = row;
        } else {
            shown = waiting("Esperando a " + network.displayName() + "…");
        }
        HBox waitingRow = new HBox(8, spinner(16), Labels.help("Esperando a que lo hagas en tu teléfono…"));
        waitingRow.setAlignment(Pos.CENTER_LEFT);
        background(() -> {
            BridgeLogin.Step next = api.await(s);
            Platform.runLater(() -> render(next));
        });
        return new VBox(14, shown, waitingRow);
    }

    private Node form(BridgeLogin.Step s) {
        VBox box = new VBox(10);
        Map<BridgeLogin.Field, Object> inputs = new LinkedHashMap<>();
        for (BridgeLogin.Field f : s.fields()) {
            Label l = new Label(LoginCopy.fieldLabel(f) + (f.optional() ? " (opcional)" : ""));
            l.getStyleClass().add("settings-label");
            Node input;
            if ("select".equals(f.type()) && !f.options().isEmpty()) {
                ComboBox<String> cb = new ComboBox<>();
                cb.getItems().setAll(f.options());
                cb.setValue(f.defaultValue() != null ? f.defaultValue() : f.options().get(0));
                cb.setAccessibleText(LoginCopy.fieldLabel(f));
                input = cb;
            } else {
                TextInputControl t = "password".equals(f.type()) ? new PasswordField() : new TextField();
                t.getStyleClass().add("login-field");
                if ("2fa_code".equals(f.type())) t.getStyleClass().add("login-code-input");
                if (f.defaultValue() != null) t.setText(f.defaultValue());
                t.setPromptText(switch (f.type()) {
                    case "phone_number" -> "+57 300 123 4567";
                    case "2fa_code" -> sampleDigits(f.pattern());
                    case "email" -> "tu@correo.com";
                    default -> "";
                });
                t.setAccessibleText(LoginCopy.fieldLabel(f));
                input = t;
            }
            inputs.put(f, input);
            box.getChildren().addAll(l, input);
            if (f.description() != null && !f.description().isBlank() && "select".equals(f.type())) {
                box.getChildren().add(Labels.help(f.description()));
            }
        }
        Label problem = Labels.help("");
        problem.getStyleClass().setAll("label", "login-error");
        Nodes.show(problem, false);
        Button next = new Button("Continuar");
        next.getStyleClass().add("primary-button");
        next.setDefaultButton(true);
        next.setOnAction(e -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (var en : inputs.entrySet()) {
                BridgeLogin.Field f = en.getKey();
                String v = en.getValue() instanceof ComboBox<?> cb ? String.valueOf(cb.getValue())
                        : ((TextInputControl) en.getValue()).getText().strip();
                if ("phone_number".equals(f.type())) v = v.replaceAll("[\\s()\\-]", "");
                if (v.isEmpty() && f.optional()) continue;
                String why = invalid(f, v);
                if (why != null) {
                    problem.setText(why);
                    Nodes.show(problem, true);
                    if (en.getValue() instanceof Node n) n.requestFocus();
                    return;
                }
                values.put(f.id(), v);
            }
            next.setDisable(true);
            next.setText("Enviando…");
            submit(s, values);
        });
        box.getChildren().addAll(problem, next);
        inputs.values().stream().findFirst().ifPresent(n -> Platform.runLater(((Node) n)::requestFocus));
        return box;
    }

    /** "1234" for a pattern like ^[0-9]{4}$, else "123456" (the usual code length). */
    static String sampleDigits(String pattern) {
        int n = LoginCopy.digits(pattern) > 0 ? LoginCopy.digits(pattern) : 6;
        return "1234567890".repeat(2).substring(0, Math.max(1, Math.min(n, 12)));
    }

    /** Spanish reason a value isn't accepted, or null. */
    static String invalid(BridgeLogin.Field f, String v) {
        if (v.isEmpty()) return "Completa «" + LoginCopy.fieldLabel(f) + "».";
        if ("phone_number".equals(f.type()) && !v.matches("\\+\\d{6,15}")) {
            return "Escribe el número con el código de país, por ejemplo +57 300 123 4567.";
        }
        if (f.pattern() != null) {
            try {
                if (!Pattern.compile(f.pattern()).matcher(v).find()) return "«" + LoginCopy.fieldLabel(f) + "» no tiene el formato esperado.";
            } catch (RuntimeException ignored) {
                // A pattern Java can't read: let the bridge validate.
            }
        }
        return null;
    }

    private Node cookies(BridgeLogin.Step s) {
        if (BridgeCookies.needsWindowCapture(s.cookies())) return capture(s);
        if (!BridgeCookies.canCollect(s.cookies())) return unsupported();
        CookieSpec spec = BridgeCookies.spec(network, s.cookies());
        VBox found = new VBox(8, new HBox(8, spinner(16), Labels.help("Buscando tu sesión en tus navegadores…")));
        Label status = Labels.help("");
        Nodes.show(status, false);
        java.util.function.Consumer<SessionCookies> send = cookies -> {
            show(waiting("Conectando…"));
            submit(s, BridgeCookies.values(s.cookies(), cookies));
        };
        Button here = new Button("Iniciar sesión aquí");
        here.getStyleClass().add("secondary-button");
        here.setMaxWidth(Double.MAX_VALUE);
        here.setAccessibleText("Abrir " + network.displayName() + " en una ventana de Unichat para iniciar sesión");
        here.setOnAction(e -> LoginWebView.open(owner.get(), network, spec, send, () -> { }));
        Button file = CookieFileButton.create(network, path -> CookieLogin.fromCookieTxt(path, spec), send, text -> {
            status.setText(text);
            Nodes.show(status, true);
        });
        CookieLogin.findBrowserSessions(spec).whenComplete((sessions, err) -> Platform.runLater(() -> {
            if (closed) return;
            found.getChildren().clear();
            List<BrowserSession> list = err != null || sessions == null ? List.of() : sessions;
            if (list.isEmpty()) found.getChildren().add(Labels.help("No encontramos una sesión abierta en tus navegadores."));
            for (BrowserSession bs : list) {
                Button use = new Button("Usar mi sesión de " + bs.browser() + (bs.profile() == null ? "" : " (" + bs.profile() + ")"));
                use.getStyleClass().add("primary-button");
                use.setMaxWidth(Double.MAX_VALUE);
                use.setOnAction(e -> send.accept(bs.cookies()));
                found.getChildren().add(use);
            }
        }));
        return new VBox(10, found, here, file, status);
    }

    /** This network needs what its page sends while you use it: only signing in here can get that. */
    private Node capture(BridgeLogin.Step s) {
        Label why = Labels.help("Inicia sesión en la ventana que se abre. Cuando veas tu página de inicio de " + network.displayName()
                + ", se cierra sola. Unichat no ve tu contraseña.");
        Button here = new Button("Iniciar sesión en " + network.displayName());
        here.getStyleClass().add("primary-button");
        here.setMaxWidth(Double.MAX_VALUE);
        here.setAccessibleText("Abrir " + network.displayName() + " en una ventana de Unichat para iniciar sesión");
        here.setOnAction(e -> openCapture(s));
        java.nio.file.Path browser = ChromeLogin.usefulFor(network) ? ChromeLogin.findChrome() : null;
        if (browser == null) return new VBox(10, why, here);
        why.setText("Se abre Chrome, aparte del tuyo y sin tocar tu sesión. Inicia sesión como siempre, también con Google. "
                + "Cuando veas tu página de inicio de " + network.displayName() + ", se cierra solo y se borra lo que quedó ahí.");
        Button chrome = new Button("Iniciar sesión en Chrome");
        chrome.getStyleClass().add("primary-button");
        chrome.setMaxWidth(Double.MAX_VALUE);
        chrome.setAccessibleText("Abrir " + network.displayName() + " en Chrome para iniciar sesión, también con Google");
        chrome.setOnAction(e -> signInWithChrome(s, browser));
        here.setText("Usar correo y contraseña aquí");
        here.getStyleClass().setAll("button", "secondary-button");
        return new VBox(10, why, chrome, here);
    }

    private void signInWithChrome(BridgeLogin.Step s, java.nio.file.Path browser) {
        ChromeLogin login = new ChromeLogin(network, s.cookies(), browser, Settings.get().cacheDir().resolve("login-chrome"));
        chromeLogin = login;
        Label wait = Labels.help("Inicia sesión en la ventana de Chrome. Unichat sigue aquí cuando termines.");
        Button cancel = new Button("Cancelar");
        cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> login.cancel());
        show(new VBox(10, new HBox(8, spinner(16), wait), cancel));
        background(() -> {
            java.util.Map<String, String> values;
            try {
                values = login.run();
            } catch (InterruptedException e) {
                values = null;
            }
            if (values == null) {
                if (!closed) Platform.runLater(() -> render(s)); // closed or cancelled: the same choice again
                return;
            }
            Platform.runLater(() -> show(waiting("Conectando…")));
            submit(s, values);
        });
    }

    private void openCapture(BridgeLogin.Step s) {
        LoginWebView.openCapturing(owner.get(), network, s.cookies(), values -> {
            show(waiting("Conectando…"));
            submit(s, values);
        }, () -> { });
    }

    /** Sends a step's answers and shows what the bridge asks next. */
    private void submit(BridgeLogin.Step s, Map<String, String> values) {
        background(() -> {
            BridgeLogin.Step after = api.submit(s, values);
            Platform.runLater(() -> render(after));
        });
    }

    private Node unsupported() {
        Label why = Labels.help("Esta forma de conectar necesita un paso que Unichat todavía no sabe mostrar.");
        Button bot = new Button("Conectar por chat con el puente");
        bot.getStyleClass().add("primary-button");
        bot.setOnAction(e -> onUseBotChat.run());
        return new VBox(10, why, bot);
    }

    private void showComplete(LoginCopy.StepText text) {
        Label check = new Label("✓");
        check.getStyleClass().add("login-done");
        Label title = new Label(text.title());
        title.getStyleClass().add("wizard-instructions");
        Label sub = Labels.help("Tus chats van a ir apareciendo en la bandeja en unos minutos.");
        Button done = new Button("Listo");
        done.getStyleClass().add("primary-button");
        done.setDefaultButton(true);
        done.setOnAction(e -> onConnected.run());
        VBox box = new VBox(12, check, title, sub, done);
        box.setAlignment(Pos.CENTER);
        show(box);
        Platform.runLater(done::requestFocus);
    }

    // ---------------------------------------------------------------- helpers

    private void error(String message, Throwable cause) {
        if (closed) return;
        String detail = cause instanceof BridgeLogin.BridgeLoginException b ? b.getMessage()
                : cause != null ? LoginTexts.rootMessage(cause) : null;
        Label what = new Label(message);
        what.getStyleClass().add("wizard-instructions");
        what.setWrapText(true);
        VBox box = new VBox(10, what);
        if (detail != null && !detail.isBlank()) {
            Label d = Labels.help("Detalle: " + detail);
            box.getChildren().add(d);
        }
        Button retry = new Button("Intentar de nuevo");
        retry.getStyleClass().add("primary-button");
        retry.setOnAction(e -> loadFlows());
        box.getChildren().add(retry);
        show(box);
    }

    /** Runs blocking bridge calls off the FX thread; failures become a friendly error screen. */
    private void background(ThrowingRunnable r) {
        Thread.ofPlatform().daemon().name("bridge-login").start(() -> { // can block for minutes
            try {
                r.run();
            } catch (Exception e) {
                if (closed) return;
                Platform.runLater(() -> error("No se pudo continuar con " + network.displayName() + ".", e));
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void show(Node n) {
        setAlignment(n instanceof ScrollPane ? Pos.TOP_LEFT : Pos.CENTER);
        getChildren().setAll(n);
    }

    /** Content from the top, scrolling when it doesn't fit (a QR plus steps is taller than small windows). */
    private static ScrollPane scroll(Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        sp.getStyleClass().add("login-scroll");
        return sp;
    }

    private static VBox numbered(List<String> steps) {
        VBox box = new VBox(6);
        for (int i = 0; i < steps.size(); i++) {
            Label l = Labels.help((i + 1) + ". " + steps.get(i));
            l.getStyleClass().setAll("label", "login-step-line");
            box.getChildren().add(l);
        }
        return box;
    }

    private static Node waiting(String text) {
        VBox box = new VBox(12, spinner(36), Labels.help(text));
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private static ProgressIndicator spinner(double size) {
        ProgressIndicator p = new ProgressIndicator();
        p.setMaxSize(size, size);
        return p;
    }


    /** QR as black modules on white, {@code size} px. */
    static WritableImage qr(String data, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, Map.of(EncodeHintType.MARGIN, 1));
            WritableImage img = new WritableImage(m.getWidth(), m.getHeight());
            PixelWriter w = img.getPixelWriter();
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) w.setColor(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            }
            return img;
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad QR data", e);
        }
    }
}

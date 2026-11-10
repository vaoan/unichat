package app.unichat.ui.login;

import app.unichat.ui.FxWait;
import app.unichat.ui.Theme;
import app.unichat.login.BridgeLogin;
import app.unichat.model.Network;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dynamic login screen against a fake WhatsApp bridge: choose a method, see the QR, the bridge confirms,
 * "conectado". Also the phone flow's form. Needs a display: {@code -Dunichat.uiTests=true}; the window is invisible.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class BridgeLoginUiTest {
    private HttpServer server;
    private final CountDownLatch scanned = new CountDownLatch(1);
    private volatile String submitted;

    @BeforeEach
    void fakeBridge() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String out;
            if (p.endsWith("/login/flows")) {
                out = "{\"flows\":[{\"id\":\"phone\",\"name\":\"Pairing code\",\"description\":\"x\"},{\"id\":\"qr\",\"name\":\"QR\",\"description\":\"y\"}]}";
            } else if (p.endsWith("/login/start/qr")) {
                out = "{\"login_id\":\"L\",\"type\":\"display_and_wait\",\"step_id\":\"fi.mau.whatsapp.login.qr\",\"display_and_wait\":{\"type\":\"qr\",\"data\":\"2@abc\"}}";
            } else if (p.endsWith("/login/start/phone")) {
                out = "{\"login_id\":\"P\",\"type\":\"user_input\",\"step_id\":\"fi.mau.whatsapp.login.phone\",\"user_input\":{\"fields\":"
                        + "[{\"type\":\"phone_number\",\"id\":\"phone_number\",\"name\":\"Phone number\"}]}}";
            } else if (p.endsWith("/user_input")) {
                submitted = body;
                out = "{\"login_id\":\"P\",\"type\":\"display_and_wait\",\"step_id\":\"fi.mau.whatsapp.login.code\",\"display_and_wait\":{\"type\":\"code\",\"data\":\"ABCD-1234\"}}";
            } else if (p.endsWith("/display_and_wait")) {
                try {
                    scanned.await(); // the user is scanning
                } catch (InterruptedException ignored) {
                    // test over
                }
                out = "{\"login_id\":\"L\",\"type\":\"complete\",\"step_id\":\"fi.mau.whatsapp.login.complete\",\"complete\":{\"user_login_id\":\"57300\"}}";
            } else {
                out = "{}";
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
    }

    @AfterEach
    void stop() throws Exception {
        scanned.countDown();
        server.stop(0);
        // A window left open goes on being drawn for every test after this one.
        fx(() -> {
            if (stage != null) stage.close();
            return null;
        });
    }

    private Stage stage;

    private Scene open(AtomicBoolean connected) throws Exception {
        BridgeLogin api = new BridgeLogin("http://127.0.0.1:" + server.getAddress().getPort() + "/_matrix/provision", "t", "@yo:localhost");
        return fx(() -> {
            stage = new Stage();
            stage.setOpacity(0);
            BridgeLoginPane pane = new BridgeLoginPane(Network.WHATSAPP, api, () -> stage, () -> connected.set(true), () -> { });
            Scene sc = new Scene(pane, 600, 700);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
    }

    @Test
    void qrLoginFromChoiceToConnected() throws Exception {
        AtomicBoolean connected = new AtomicBoolean();
        Scene scene = open(connected);
        await("the choice of methods", () -> button(scene, "Escanear un código QR") != null);
        assertTrue(text(scene).contains("¿Cómo quieres conectar WhatsApp?"));
        shot(scene, "1-elegir");
        assertTrue(text(scene).indexOf("Escanear un código QR") < text(scene).indexOf("Usar tu número de teléfono"), "QR first");
        fx(() -> { buttonNow(scene, "Escanear un código QR").fire(); return null; });

        await("the QR", () -> fx0(() -> scene.getRoot().lookupAll("*").stream().anyMatch(n -> n instanceof ImageView)));
        assertTrue(text(scene).contains("Escanea el código con tu teléfono"));
        assertTrue(text(scene).contains("Dispositivos vinculados"));
        shot(scene, "2-qr");

        scanned.countDown();
        await("connected", () -> button(scene, "Listo") != null);
        assertTrue(text(scene).contains("WhatsApp está conectado"));
        shot(scene, "3-listo");
        fx(() -> { buttonNow(scene, "Listo").fire(); return null; });
        assertTrue(connected.get());
    }

    @Test
    void phoneFlowValidatesThenShowsTheCode() throws Exception {
        Scene scene = open(new AtomicBoolean());
        await("the choice of methods", () -> button(scene, "Usar tu número de teléfono") != null);
        fx(() -> { buttonNow(scene, "Usar tu número de teléfono").fire(); return null; });
        await("the phone field", () -> fx0(() -> field(scene) != null));
        fx(() -> { field(scene).setText("300 123 4567"); buttonNow(scene, "Continuar").fire(); return null; });
        await("the country code asked for", () -> text(scene).contains("código de país"));
        shot(scene, "4-telefono-error");
        assertTrue(submitted == null, "nothing sent while invalid");
        fx(() -> { field(scene).setText("+57 300 123 4567"); buttonNow(scene, "Continuar").fire(); return null; });
        await("the pairing code", () -> text(scene).contains("ABCD-1234"));
        assertTrue(submitted.contains("\"phone_number\":\"+573001234567\""), submitted);
        assertTrue(text(scene).contains("Escribe este código en tu teléfono"));
        shot(scene, "5-codigo");
    }

    // ---------------------------------------------------------------- helpers

    /** -Dunichat.loginShots=<dir>: saves a PNG of the (invisible) window, for checking the design. */
    private static void shot(Scene s, String name) throws Exception {
        String dir = System.getProperty("unichat.loginShots");
        if (dir == null) return;
        java.awt.image.BufferedImage img = fx(() -> javafx.embed.swing.SwingFXUtils.fromFXImage(s.snapshot(null), null));
        javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, name + ".png"));
    }

    /** From a test thread. */
    private static Clickable button(Scene s, String startsWith) {
        try {
            return fx(() -> buttonNow(s, startsWith));
        } catch (Exception e) {
            return null;
        }
    }

    /** A Button or a card announced as a button, by text or screen-reader text. On the FX thread. */
    private static Clickable buttonNow(Scene s, String startsWith) {
        for (Node n : s.getRoot().lookupAll("*")) {
            if (!n.isVisible()) continue;
            if (n instanceof Button b && ((b.getText() != null && b.getText().startsWith(startsWith))
                    || (b.getAccessibleText() != null && b.getAccessibleText().startsWith(startsWith)))) return b::fire;
            if (!(n instanceof Button) && n.getAccessibleRole() == javafx.scene.AccessibleRole.BUTTON
                    && n.getAccessibleText() != null && n.getAccessibleText().startsWith(startsWith)) {
                return () -> n.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                        javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface Clickable {
        void fire();
    }

    private static TextField field(Scene s) {
        return s.getRoot().lookupAll("*").stream().filter(n -> n instanceof TextField).map(n -> (TextField) n).findFirst().orElse(null);
    }

    private static String text(Scene s) {
        try {
            return fx(() -> {
                StringBuilder sb = new StringBuilder();
                for (Node n : s.getRoot().lookupAll("*")) if (n instanceof Label l && l.isVisible()) sb.append(l.getText()).append('\n');
                return sb.toString();
            });
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean fx0(Supplier<Boolean> s) {
        try {
            return fx(s);
        } catch (Exception e) {
            return false;
        }
    }

    private static <T> T fx(Supplier<T> s) throws Exception {
        return FxWait.fx(s::get);
    }

    private static void await(String what, BooleanSupplier c) throws Exception {
        FxWait.until(what, c::getAsBoolean);
    }
}

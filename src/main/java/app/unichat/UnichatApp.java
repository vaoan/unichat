package app.unichat;

import app.unichat.matrix.MatrixChatService;
import app.unichat.server.LocalServer;
import app.unichat.service.ChatService;
import app.unichat.service.MockChatService;
import app.unichat.settings.Settings;
import app.unichat.ui.DockIcon;
import app.unichat.ui.LoginView;
import app.unichat.ui.MacQuit;
import app.unichat.ui.MainView;
import app.unichat.ui.Notifier;
import app.unichat.ui.Theme;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;

/** Chooses login vs. main view, owns the window, tray icon and the active {@link ChatService}. */
public final class UnichatApp extends Application {
    private Stage stage;
    private Scene scene;
    private Notifier notifier;
    private ChatService service;
    private MainView mainView;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        stage = primary;
        stage.setTitle("Unichat");
        stage.setMinWidth(760);
        stage.setMinHeight(480);
        for (String size : new String[]{"16", "32", "64", "128", "256"}) {
            try (InputStream in = getClass().getResourceAsStream("/app/unichat/icons/app-" + size + ".png")) {
                if (in != null) stage.getIcons().add(new Image(in));
            } catch (Exception ignored) {
                // Icons are optional.
            }
        }
        Settings s = Settings.get();
        stage.setWidth(s.getDouble("window.width", 1180));
        stage.setHeight(s.getDouble("window.height", 760));
        double x = s.getDouble("window.x", Double.NaN), y = s.getDouble("window.y", Double.NaN);
        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            stage.setX(x);
            stage.setY(y);
        }

        // Tray/balloon clicks arrive on the AWT thread; the macOS card on the FX thread.
        notifier = new Notifier(this::showWindow, id -> {
            showWindow();
            Platform.runLater(() -> {
                if (mainView != null) mainView.openChatId(id);
            });
        }, this::quit);
        Platform.setImplicitExit(!notifier.hasTray());
        // macOS "Salir" (⌘Q, app menu, Dock, logout) quits for real; closing the window only hides it in the tray.
        MacQuit.install(this::quit);

        scene = new Scene(new javafx.scene.layout.StackPane(), stage.getWidth(), stage.getHeight());
        Theme.register(scene);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            saveGeometry();
            if (toTray()) {
                e.consume();
                hideToTray();
            } else {
                quit();
            }
        });

        LogFiles.scheduleCleanup();
        if (s.localServer()) startLocalServer();
        ChatService restored = switch (s.backend() == null ? "" : s.backend()) {
            case "demo" -> new MockChatService();
            case "matrix" -> MatrixChatService.fromSettings();
            default -> null;
        };
        if (restored != null) showMain(restored); else showLogin();
        if (System.getProperty("unichat.snapshot") != null) {
            // Diagnostics run: invisible and behind other windows so it never takes the user's keyboard.
            stage.setOpacity(0);
            stage.setX(-10000);
        }
        stage.show();
        if (System.getProperty("unichat.snapshot") != null) stage.toBack();
        // -Dunichat.startLogin=<protocol id>: open Ajustes > Cuentas with that network's login wizard.
        String startLogin = System.getProperty("unichat.startLogin");
        if (startLogin != null && mainView != null) {
            javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            wait.setOnFinished(e -> mainView.addAccount(app.unichat.model.Network.fromProtocolId(startLogin)));
            wait.play();
        }
        snapshotIfRequested();
    }

    /**
     * Diagnostics for screenshots/CI: {@code -Dunichat.snapshot=out.png} writes the window to a PNG
     * and exits; {@code -Dunichat.openChat=<id>} opens a chat first, {@code -Dunichat.info=true} the info panel,
     * {@code -Dunichat.openSpace=<id>} a server's channels,
     * {@code -Dunichat.overlay=command|newchat|add|picker|login:<net>|settings[:Page]} the command bar, connect-network sheet or settings,
     * {@code -Dunichat.network=<protocol id>} filters the inbox to one network.
     */
    private void snapshotIfRequested() {
        String out = System.getProperty("unichat.snapshot");
        if (out == null) return;
        String chatId = System.getProperty("unichat.openChat");
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
        wait.setOnFinished(e -> {
            if (chatId != null && mainView != null) mainView.openChatId(chatId);
            if (Boolean.getBoolean("unichat.info") && mainView != null) mainView.toggleInfo();
            String panelTab = System.getProperty("unichat.panel");
            if (panelTab != null && mainView != null) mainView.showPanel(app.unichat.ui.SidePanel.Tab.valueOf(panelTab));
            String net = System.getProperty("unichat.network");
            if (net != null && mainView != null) mainView.showNetwork(app.unichat.model.Network.fromProtocolId(net));
            String space = System.getProperty("unichat.openSpace");
            if (space != null && mainView != null) mainView.showSpace(space);
            String overlay = System.getProperty("unichat.overlay", "");
            if (mainView != null && overlay.equals("command")) mainView.showCommandBar();
            if (mainView != null && overlay.equals("newchat")) mainView.showNewChat();
            if (mainView != null && overlay.equals("add")) mainView.showAccounts();
            if (mainView != null && overlay.equals("picker")) mainView.showAccounts().showPicker();
            if (mainView != null && overlay.startsWith("login:")) {
                mainView.addAccount(app.unichat.model.Network.fromProtocolId(overlay.substring(6)));
            }
            if (mainView != null && overlay.startsWith("settings")) {
                mainView.showSettings();
                if (overlay.contains(":")) app.unichat.ui.SettingsWindow.select(overlay.substring(overlay.indexOf(':') + 1));
            }
            javafx.animation.PauseTransition shoot = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(Double.parseDouble(System.getProperty("unichat.snapshotDelay", "1.5"))));
            shoot.setOnFinished(ev -> {
                try {
                    var settings = app.unichat.ui.SettingsWindow.current();
                    var img = (settings != null ? settings.getScene() : scene).snapshot(null);
                    javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(img, null), "png", new java.io.File(out));
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                Thread guard = new Thread(() -> {
                    try { Thread.sleep(4000); } catch (InterruptedException ignored) { }
                    Runtime.getRuntime().halt(0);
                });
                guard.setDaemon(true);
                guard.start();
                quit();
            });
            shoot.play();
        });
        wait.play();
    }

    private void showLogin() {
        if (mainView != null) mainView.dispose();
        mainView = null;
        scene.setRoot(new LoginView(svc -> showMain(svc), () -> {
            Settings.get().setBackend("demo");
            showMain(new MockChatService());
        }, this::openLink));
        Theme.applyAll();
    }

    /**
     * Installs whatever is missing and starts the local server in the background; the chat list shows from cache
     * meanwhile and the sync connects as soon as the server answers.
     */
    private void startLocalServer() {
        LocalServer local = LocalServer.get();
        if (local == null) return;
        MatrixChatService.setBridgeProvisioner(provisioner(local));
        Thread.ofVirtual().name("local-server").start(() -> {
            try {
                local.ensureRunning(msg -> { });
            } catch (Exception e) {
                System.err.println("Local server failed to start: " + e);
            }
        });
    }

    /** Adding a network on the local server installs and starts its bridge first. */
    private static MatrixChatService.BridgeProvisioner provisioner(LocalServer local) {
        return new MatrixChatService.BridgeProvisioner() {
            @Override public void ensure(app.unichat.model.Network n) throws Exception {
                local.ensureBridge(n.protocolId(), msg -> { });
            }

            @Override public boolean provides(app.unichat.model.Network n) {
                return local.provides(n.protocolId());
            }

            @Override public String provisioningUrl(app.unichat.model.Network n) {
                return local.provisioningUrl(n.protocolId());
            }

            @Override public app.unichat.server.BridgeStates states() {
                return local.states();
            }
        };
    }

    private void showMain(ChatService svc) {
        if (Settings.get().localServer()) {
            LocalServer local = LocalServer.get();
            if (local != null) MatrixChatService.setBridgeProvisioner(provisioner(local));
        }
        service = svc;
        mainView = new MainView(svc, stage, notifier, this::logout, this::openLink);
        scene.setRoot(mainView);
        Theme.applyAll();
        svc.start();
    }

    private void logout() {
        ChatService svc = service;
        service = null;
        if (svc == null) return;
        svc.logout().whenComplete((v, err) -> Platform.runLater(() -> {
            svc.stop();
            Settings.get().clearSession();
            if (Settings.get().localServer()) {
                Settings.get().setLocalServer(false);
                MatrixChatService.setBridgeProvisioner(null);
                LocalServer local = LocalServer.get();
                if (local != null) Thread.ofVirtual().start(local::stop);
            }
            showLogin();
        }));
    }

    private void openLink(String url) {
        getHostServices().showDocument(url);
    }

    private boolean toTray() {
        return notifier.hasTray() && Settings.get().minimizeToTray();
    }

    private void hideToTray() {
        stage.hide();
        DockIcon.hide();
    }

    private void showWindow() {
        Platform.runLater(() -> {
            DockIcon.show();
            stage.show();
            stage.setIconified(false);
            stage.toFront();
            stage.requestFocus();
        });
    }

    private void saveGeometry() {
        Settings s = Settings.get();
        if (stage.isIconified() || stage.isMaximized()) return;
        s.set("window.width", Double.toString(stage.getWidth()));
        s.set("window.height", Double.toString(stage.getHeight()));
        s.set("window.x", Double.toString(stage.getX()));
        s.set("window.y", Double.toString(stage.getY()));
    }

    private void quit() {
        Platform.runLater(() -> {
            if (stage.isShowing()) saveGeometry();
            if (service != null) service.stop();
            LocalServer local = Settings.get().localServer() ? LocalServer.get() : null;
            if (local != null) local.stop();
            notifier.dispose();
            Platform.exit();
            // Exit off the FX thread: System.exit on it can deadlock with AWT's shutdown on macOS.
            new Thread(() -> System.exit(0), "exit").start();
        });
    }
}

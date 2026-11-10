package app.unichat;

import app.unichat.matrix.MatrixLogin;
import app.unichat.matrix.BridgeProvisioner;
import app.unichat.matrix.MatrixChatService;
import app.unichat.model.Network;
import app.unichat.bridge.BridgeStates;
import app.unichat.server.LocalServer;
import app.unichat.server.LogFiles;
import app.unichat.service.ChatService;
import app.unichat.service.MockChatService;
import app.unichat.service.PreferencesFile;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.ui.Browser;
import app.unichat.ui.DockIcon;
import app.unichat.ui.TrayReopen;
import app.unichat.ui.LoginView;
import app.unichat.ui.SettingsWindow;
import app.unichat.ui.MacQuit;
import app.unichat.ui.MainView;
import app.unichat.ui.Notifier;
import app.unichat.ui.Theme;
import app.unichat.update.UpdateChecker;
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
    private volatile ChatService service;
    private MainView mainView;
    /** The person's own storage, kept up to date while a real session runs; null in the demo. */
    private app.unichat.cloud.Cloud cloud;

    public static void main(String[] args) {
        if (System.getProperty("unichat.snapshot") != null) {
            // Diagnostics never touch the screen: JavaFX's headless platform draws the PNG, and no menu-bar icon,
            // notification or window appears while the person is working.
            System.setProperty("glass.platform", "Headless");
            System.setProperty("prism.order", "sw");
            System.setProperty("java.awt.headless", "true");
        } else {
            System.setProperty("java.awt.headless", "false");
        }
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        stage = primary;
        Recovery.watchUi(); // a window that stops answering starts Unichat again
        // -Dunichat.language (tests, snapshots) wins over what was chosen in Ajustes.
        I18n.use(System.getProperty("unichat.language", Settings.get().language()));
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
        notifier = new Notifier(this::showWindow, () -> Platform.runLater(() -> {
            saveGeometry();
            hideToTray();
        }), id -> {
            showWindow();
            Platform.runLater(() -> {
                if (mainView != null) mainView.openChatId(id);
            });
        }, this::quit);
        // The tray is built on AWT's thread: what depends on it waits until it exists.
        notifier.ready().thenAccept(tray -> Platform.runLater(() -> {
            Platform.setImplicitExit(!tray);
            DockIcon.setTrayAvailable(tray);
        }));
        javafx.beans.InvalidationListener shown = o -> notifier.setWindowShown(stage.isShowing() && !stage.isIconified());
        stage.showingProperty().addListener(shown);
        stage.iconifiedProperty().addListener(shown);
        watchFocus();
        // macOS "Salir" (⌘Q, app menu, Dock, logout) quits for real; closing the window only hides it in the tray.
        MacQuit.install(this::quit, () -> {
            if (TrayReopen.shouldShow(stage.isShowing(), System.currentTimeMillis() - hiddenAt)) showWindow();
        });

        I18n.onChange(() -> Platform.runLater(this::relocalize));

        scene = new Scene(new javafx.scene.layout.StackPane(), stage.getWidth(), stage.getHeight());
        Theme.register(scene);
        stage.setScene(scene);
        // Without a Dock icon there's no app menu either, and ⌘Q lives in that menu.
        if (MainView.MAC) scene.getAccelerators().put(new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.Q,
                javafx.scene.input.KeyCombination.SHORTCUT_DOWN), this::quit);
        stage.setOnCloseRequest(e -> {
            saveGeometry();
            if (toTray()) {
                e.consume();
                hideToTray();
            } else {
                quit();
            }
        });

        // Ending any other way than quit() (logging out of the computer, kill): what was chosen last still reaches the disk.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ChatService svc = service;
            if (svc != null) svc.stop();
        }, "preferences-shutdown"));
        LogFiles.scheduleCleanup();
        if (s.localServer()) startLocalServer();
        ChatService restored = switch (s.backend() == null ? "" : s.backend()) {
            case "demo" -> demoService();
            case "matrix" -> MatrixLogin.fromSettings();
            default -> null;
        };
        if (restored != null) showMain(restored); else showLogin();
        if (System.getProperty("unichat.snapshot") != null) {
            // Diagnostics run: invisible and behind other windows so it never takes the user's keyboard.
            stage.setOpacity(0);
            stage.setX(-10000);
        }
        // A menu bar app isn't made active by opening: the window would open behind the others. Diagnostics never take focus.
        if (System.getProperty("unichat.snapshot") == null) DockIcon.show();
        stage.show();
        if (System.getProperty("unichat.snapshot") != null) stage.toBack();
        // -Dunichat.startLogin=<protocol id>: open Ajustes > Cuentas with that network's login wizard.
        String startLogin = System.getProperty("unichat.startLogin");
        if (startLogin != null && mainView != null) {
            javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            wait.setOnFinished(e -> mainView.addAccount(Network.fromProtocolId(startLogin)));
            wait.play();
        }
        String snapshot = System.getProperty("unichat.snapshot");
        if (snapshot != null) new SnapshotRun(mainView, scene, this::quit).start(snapshot);
        else UpdateChecker.get().start(); // diagnostics never ask GitHub
    }

    /** The demo keeps its choices and folders in the same files as the real service. */
    private static MockChatService demoService() {
        return new MockChatService(new PreferencesFile(
                Settings.dataDir().resolve("preferences.json"), Settings.dataDir().resolve("server-seen.json")));
    }

    private void showLogin() {
        if (mainView != null) mainView.dispose();
        mainView = null;
        scene.setRoot(new LoginView(svc -> showMain(svc), () -> {
            Settings.get().setBackend("demo");
            showMain(demoService());
        }, Browser::open));
        Theme.applyAll();
    }

    /**
     * Installs whatever is missing and starts the local server in the background; the chat list shows from cache
     * meanwhile and the sync connects as soon as the server answers.
     */
    private void startLocalServer() {
        LocalServer local = LocalServer.get();
        if (local == null) return;
        Thread.ofVirtual().name("local-server").start(() -> {
            try {
                local.ensureRunning(msg -> { });
            } catch (Exception e) {
                System.err.println("Local server failed to start: " + e);
            }
        });
    }

    /** Adding a network on the local server installs and starts its bridge first. */
    private static BridgeProvisioner provisioner(LocalServer local) {
        return new BridgeProvisioner() {
            @Override public void ensure(Network n) throws Exception {
                local.ensureBridge(n.protocolId(), msg -> { });
            }

            @Override public boolean provides(Network n) {
                return local.provides(n.protocolId());
            }

            @Override public String provisioningUrl(Network n) {
                return local.provisioningUrl(n.protocolId());
            }

            @Override public BridgeStates states() {
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
        // Chats that save what they receive do it from the first sync, window shown or not; the menu-bar icon shows it.
        app.unichat.autosave.AutoSave.of(svc).setProgress(new app.unichat.autosave.AutoSave.Progress() {
            @Override public void changed(int done, int total) { notifier.setDownloadProgress(done, total); }
            @Override public void finished(int saved, app.unichat.model.Chat chat) {
                notifier.downloadsDone(saved, chat);
                if (cloud != null) cloud.syncNow(); // what was just saved goes up now, not at the next round
            }
            @Override public void failed(String what, app.unichat.model.Chat chat, String why) { notifier.downloadFailed(what, chat, why); }
        });
        mainView = new MainView(svc, stage, notifier, this::logout, Browser::open);
        // Never in the demo (its made-up chats would reach the person's account), but for a snapshot of Ajustes → Nube.
        if (!(svc instanceof app.unichat.service.MockChatService) || System.getProperty("unichat.snapshot") != null) {
            cloud = app.unichat.cloud.Cloud.create(Settings.dataDir(), svc::preferencesJson, svc::adoptPreferences,
                    () -> Platform.runLater(() -> { if (mainView != null) mainView.settingsSynced(); }),
                    () -> app.unichat.autosave.AutoSave.of(svc).folders());
            app.unichat.cloud.Cloud c = cloud;
            // What it's doing: on the tray (its menu's lines and ring) and on the settings gear.
            c.addListener(() -> {
                app.unichat.cloud.Cloud.Status st = c.status();
                notifier.setCloud(st);
                Platform.runLater(() -> { if (mainView != null) mainView.cloudChanged(st); });
            });
            cloud.start(java.time.Duration.ofMinutes(1));
        }
        mainView.useCloud(cloud);
        scene.setRoot(mainView);
        Theme.applyAll();
        svc.start();
    }

    /**
     * Rebuilds the window in the language just chosen: the same chat stays open, and the settings window, where the
     * change was made, reopens on the same page. Texts already built (open viewers, the demo's chats) keep the old one.
     */
    private void relocalize() {
        notifier.relabel();
        if (mainView == null || service == null) return;
        String chat = mainView.currentChatId();
        String page = SettingsWindow.currentPage();
        if (SettingsWindow.current() != null) SettingsWindow.current().close();
        mainView.dispose();
        mainView = new MainView(service, stage, notifier, this::logout, Browser::open);
        scene.setRoot(mainView);
        Theme.applyAll();
        if (chat != null) mainView.openChatId(chat);
        if (page != null) {
            mainView.showSettings();
            SettingsWindow.select(page);
        }
    }

    private void logout() {
        ChatService svc = service;
        service = null;
        if (svc == null) return;
        if (cloud != null) cloud.stop();
        cloud = null;
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

    private long hiddenAt;

    private boolean toTray() {
        return notifier.hasTray() && Settings.get().minimizeToTray();
    }

    private void hideToTray() {
        hiddenAt = System.currentTimeMillis();
        stage.hide();
        DockIcon.hide();
    }

    /**
     * Tells the menu-bar icon whether any Unichat window has the focus (the settings or a photo count too). Each window
     * is watched while it's on screen: hidden ones leave {@code Window.getWindows()}, and come back when shown.
     */
    private void watchFocus() {
        app.unichat.ui.AppWindows.install(stage);
        javafx.beans.value.ChangeListener<Boolean> focus = (o, a, b) -> Platform.runLater(() ->
                notifier.setWindowFocused(javafx.stage.Window.getWindows().stream().anyMatch(javafx.stage.Window::isFocused)));
        for (javafx.stage.Window w : javafx.stage.Window.getWindows()) w.focusedProperty().addListener(focus);
        javafx.stage.Window.getWindows().addListener((javafx.collections.ListChangeListener<javafx.stage.Window>) c -> {
            while (c.next()) {
                for (javafx.stage.Window w : c.getRemoved()) w.focusedProperty().removeListener(focus);
                for (javafx.stage.Window w : c.getAddedSubList()) w.focusedProperty().addListener(focus);
            }
        });
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
        s.setWindowGeometry(stage.getWidth(), stage.getHeight(), stage.getX(), stage.getY());
    }

    /** The window goes at once; what has to stop stops off the UI thread (it can take seconds per bridge), then the app ends. */
    private void quit() {
        Platform.runLater(() -> {
            if (stage.isShowing()) saveGeometry();
            stage.hide();
            notifier.dispose();
            app.unichat.cloud.Cloud c = cloud;
            ChatService s = service;
            LocalServer local = Settings.get().localServer() ? LocalServer.get() : null;
            java.util.List<Runnable> stops = new java.util.ArrayList<>();
            if (c != null) stops.add(c::stop);
            if (s != null) stops.add(s::stop);
            if (local != null) stops.add(local::stop);
            // Exit off the FX thread: System.exit on it can deadlock with AWT's shutdown on macOS.
            Shutdown.stopThenEnd(stops, () -> {
                Platform.exit();
                System.exit(0);
            });
        });
    }
}

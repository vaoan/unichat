package app.unichat;

import app.unichat.model.Network;
import app.unichat.ui.MainView;
import app.unichat.ui.SettingsWindow;
import app.unichat.ui.SidePanel;
import javafx.animation.PauseTransition;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.util.Duration;

import java.io.File;

/**
 * Diagnostics for screenshots/CI: {@code -Dunichat.snapshot=out.png} writes the window to a PNG
 * and exits; {@code -Dunichat.openChat=<id>} opens a chat first, {@code -Dunichat.info=true} the info panel,
 * {@code -Dunichat.openSpace=<id>} a server's channels,
 * {@code -Dunichat.openFolders=true} the list of folders, {@code -Dunichat.openFolder=<name>} inside one,
 * {@code -Dunichat.overlay=command|newchat|add|picker|folder|folderDelete|login:<net>|settings[:Page]} the command bar, connect-network sheet or settings,
 * {@code -Dunichat.network=<protocol id>} filters the inbox to one network.
 */
final class SnapshotRun {
    private final MainView mainView;
    private final Scene scene;
    private final Runnable quit;

    /** {@code mainView} null: logged out, the window is shot as it is. */
    SnapshotRun(MainView mainView, Scene scene, Runnable quit) {
        this.mainView = mainView;
        this.scene = scene;
        this.quit = quit;
    }

    void start(String out) {
        PauseTransition wait = new PauseTransition(Duration.seconds(1.5));
        wait.setOnFinished(e -> {
            if (mainView != null) setUp();
            PauseTransition shoot = new PauseTransition(Duration.seconds(Double.parseDouble(System.getProperty("unichat.snapshotDelay", "1.5"))));
            shoot.setOnFinished(ev -> shoot(out));
            shoot.play();
        });
        wait.play();
    }

    private void setUp() {
        String chatId = System.getProperty("unichat.openChat");
        if (chatId != null) mainView.openChatId(chatId);
        if (Boolean.getBoolean("unichat.info")) mainView.toggleInfo();
        String panelTab = System.getProperty("unichat.panel");
        if (panelTab != null) mainView.showPanel(SidePanel.Tab.valueOf(panelTab));
        String net = System.getProperty("unichat.network");
        if (net != null) mainView.showNetwork(Network.fromProtocolId(net));
        String space = System.getProperty("unichat.openSpace");
        if (space != null) mainView.showSpace(space);
        String folder = System.getProperty("unichat.openFolder");
        if (folder != null || Boolean.getBoolean("unichat.openFolders")) mainView.showFolders(folder);
        String draft = System.getProperty("unichat.draft"); // text in the message box: the send button shows
        if (draft != null) mainView.type(draft);
        String upload = System.getProperty("unichat.upload"); // files sent as a batch to the open chat: the upload bar
        if (upload != null) mainView.sendFiles(java.util.Arrays.stream(upload.split(",")).map(File::new).toList(),
                // -Dunichat.uploadAs=PHOTO|DOCUMENT (-Dunichat.uploadAsDocument=true, as before, is DOCUMENT)
                Boolean.getBoolean("unichat.uploadAsDocument") ? app.unichat.service.SendAs.DOCUMENT
                        : app.unichat.service.SendAs.valueOf(System.getProperty("unichat.uploadAs", "AS_IS")));
        String overlay = System.getProperty("unichat.overlay", "");
        if (overlay.equals("command")) mainView.showCommandBar();
        if (overlay.equals("newchat")) mainView.showNewChat();
        if (overlay.equals("add")) mainView.showAccounts();
        if (overlay.startsWith("folder")) mainView.showFolderDialog(overlay.equals("folderDelete"));
        if (overlay.equals("picker")) mainView.showAccounts().showPicker();
        if (overlay.startsWith("login:")) mainView.addAccount(Network.fromProtocolId(overlay.substring(6)));
        if (overlay.startsWith("settings")) {
            mainView.showSettings();
            if (overlay.contains(":")) SettingsWindow.select(overlay.substring(overlay.indexOf(':') + 1));
        }
    }

    private void shoot(String out) {
        try {
            var settings = SettingsWindow.current();
            // -Dunichat.snapshotScale=2: as a Retina screen draws it, to check alignment pixel by pixel.
            double k = Double.parseDouble(System.getProperty("unichat.snapshotScale", "1"));
            var params = new javafx.scene.SnapshotParameters();
            params.setTransform(javafx.scene.transform.Transform.scale(k, k));
            var shot = settings != null ? settings.getScene() : scene;
            var img = shot.getRoot().snapshot(params, null);
            javax.imageio.ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(out));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        Thread guard = new Thread(() -> {
            try { Thread.sleep(4000); } catch (InterruptedException ignored) { }
            Runtime.getRuntime().halt(0);
        });
        guard.setDaemon(true);
        guard.start();
        quit.run();
    }
}

package app.unichat;

import app.unichat.model.Network;
import app.unichat.ui.MainView;
import app.unichat.ui.settings.SettingsWindow;
import app.unichat.ui.chats.SidePanel;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * {@code -Dunichat.overlay=command|newchat|add|picker|folder|folderDelete|cloak|chatOptions|forward|select|delete|savedText[:none]|fixing|queue|capture[:sheet]|login:<net>|settings[:Page]} the command bar, connect-network sheet or settings,
 * {@code -Dunichat.network=<protocol id>} filters the inbox to one network, {@code -Dunichat.search=<text>} searches it.
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
        String stickerSet = System.getProperty("unichat.stickerSet");
        if (stickerSet != null) mainView.showStickerSet(stickerSet);
        String net = System.getProperty("unichat.network");
        if (net != null) mainView.showNetwork(Network.fromProtocolId(net));
        String space = System.getProperty("unichat.openSpace");
        if (space != null) mainView.showSpace(space);
        String folder = System.getProperty("unichat.openFolder");
        if (folder != null || Boolean.getBoolean("unichat.openFolders")) mainView.showFolders(folder);
        String draft = System.getProperty("unichat.draft"); // text in the message box: the send button shows
        if (draft != null) mainView.type(draft);
        String search = System.getProperty("unichat.search"); // text in the chat list's search box: its ✕ shows
        if (search != null && scene.getRoot().lookup(".search-box .search-field") instanceof javafx.scene.control.TextField f) f.setText(search);
        String upload = System.getProperty("unichat.upload"); // files sent as a batch to the open chat: the upload bar
        if (upload != null) mainView.sendFiles(java.util.Arrays.stream(upload.split(",")).map(File::new).toList(),
                // -Dunichat.uploadAs=PHOTO|DOCUMENT (-Dunichat.uploadAsDocument=true, as before, is DOCUMENT)
                Boolean.getBoolean("unichat.uploadAsDocument") ? app.unichat.service.SendAs.DOCUMENT
                        : app.unichat.service.SendAs.valueOf(System.getProperty("unichat.uploadAs", "AS_IS")));
        showOverlay(System.getProperty("unichat.overlay", ""));
    }

    /** -Dunichat.overlay: the one sheet, window or dialog to show over the main view. */
    private void showOverlay(String overlay) {
        if (overlay.equals("command")) mainView.showCommandBar();
        else if (overlay.equals("newchat")) mainView.showNewChat();
        else if (overlay.equals("add")) mainView.showAccounts();
        else if (overlay.startsWith("folder")) mainView.showFolderDialog(overlay.equals("folderDelete"));
        else if (overlay.equals("picker")) mainView.showAccounts().showPicker();
        else if (overlay.equals("cloak")) mainView.showProtectPhoto();
        else if (overlay.equals("chatOptions")) mainView.showChatOptions();
        else if (overlay.equals("forward") || overlay.equals("select")) mainView.showForwarding(overlay.equals("select"));
        else if (overlay.equals("delete")) mainView.showDeleting();
        else if (overlay.startsWith("savedText")) mainView.showSavedText(overlay.equals("savedText:none"));
        else if (overlay.equals("fixing")) mainView.showFixing();
        else if (overlay.equals("queue")) mainView.showQueue();
        else if (overlay.startsWith("capture")) mainView.showCapture(overlay.equals("capture:sheet"));
        else if (overlay.startsWith("login:")) mainView.addAccount(Network.fromProtocolId(overlay.substring(6)));
        else if (overlay.startsWith("settings")) {
            mainView.showSettings();
            if (overlay.contains(":")) SettingsWindow.select(overlay.substring(overlay.indexOf(':') + 1));
        }
    }

    @SuppressFBWarnings(value = "INFORMATION_EXPOSURE_THROUGH_AN_ERROR_MESSAGE", justification = "the snapshot skill's own run: the trace goes to its stderr, read by whoever ran it")
    private void shoot(String out) {
        try {
            var settings = SettingsWindow.current();
            // -Dunichat.snapshotScale=2: as a Retina screen draws it, to check alignment pixel by pixel.
            double k = Double.parseDouble(System.getProperty("unichat.snapshotScale", "1"));
            var params = new javafx.scene.SnapshotParameters();
            params.setTransform(javafx.scene.transform.Transform.scale(k, k));
            var shot = settings != null ? settings.getScene() : scene;
            // -Dunichat.settingsScroll=1: the settings page scrolled to its end (its window has a fixed size).
            String scroll = System.getProperty("unichat.settingsScroll");
            if (settings != null && scroll != null) {
                for (var n : shot.getRoot().lookupAll(".scroll-pane")) {
                    if (n instanceof javafx.scene.control.ScrollPane sp) sp.setVvalue(Double.parseDouble(scroll));
                }
                shot.getRoot().applyCss();
                shot.getRoot().layout();
            }
            javafx.scene.Node subject = shot.getRoot();
            // -Dunichat.settingsFull=true: the whole settings page, top to bottom, not just what the window shows.
            if (settings != null && Boolean.getBoolean("unichat.settingsFull")) {
                for (var n : shot.getRoot().lookupAll(".scroll-pane")) {
                    if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() instanceof javafx.scene.layout.Region page) {
                        double w = sp.getViewportBounds().getWidth();
                        page.resize(w, page.prefHeight(w));
                        page.layout();
                        subject = page;
                        break; // the page's own: the first, outermost (a text box inside has one too)
                    }
                }
            }
            if (subject != shot.getRoot() && shot.getRoot() instanceof javafx.scene.layout.Region r && r.getBackground() != null
                    && !r.getBackground().getFills().isEmpty()) params.setFill(r.getBackground().getFills().getFirst().getFill());
            var img = subject.snapshot(params, null);
            javax.imageio.ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", java.nio.file.Path.of(out).toFile());
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
        }
        Thread guard = new Thread(() -> {
            try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Runtime.getRuntime().halt(0); // NOPMD: DoNotTerminateVM, a snapshot run ends the JVM once the picture is out
        });
        guard.setDaemon(true);
        guard.start();
        quit.run();
    }
}

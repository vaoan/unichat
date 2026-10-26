package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.settings.Settings;

import javax.imageio.ImageIO;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Desktop notifications and unread badge through AWT: a tray icon (Windows notification area /
 * macOS menu bar) with balloon messages, plus the dock/taskbar badge where supported.
 */
public final class Notifier {
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private volatile TrayIcon tray;
    private volatile String lastChatId;
    private final DesktopToast toast;

    /** {@code onOpen} receives a chat id (or null) when the user clicks the tray icon or a notification. */
    public Notifier(Runnable onShow, Consumer<String> onOpen, Runnable onQuit) {
        toast = MAC ? new DesktopToast(onOpen) : null;
        this.onOpen = onOpen;
        if (GraphicsEnvironment.isHeadless()) { // tests, snapshot runs: no tray, and AWT menus can't be built
            this.menu = null;
            return;
        }
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("Abrir Unichat");
        open.addActionListener(e -> onShow.run());
        MenuItem quit = new MenuItem("Salir");
        quit.addActionListener(e -> onQuit.run());
        menu.add(open);
        menu.addSeparator();
        menu.add(quit);
        this.menu = menu;
        // macOS: the native item (narrow, real menu, like Apple's own); the AWT TrayIcon if it's missing or dies.
        if (MAC && startNativeBar()) return;
        addAwtTray();
    }

    private final PopupMenu menu;
    private final Consumer<String> onOpen;
    private volatile NativeBar bar;
    private volatile String tooltip = "Unichat";
    private volatile boolean disposed;

    private boolean startNativeBar() {
        NativeBar b = NativeBar.start(barHelper(), menu, () -> EventQueue.invokeLater(() -> {
            bar = null;
            if (tray == null && !disposed) addAwtTray(); // the helper died: keep a tray icon
        }));
        if (b == null) return false;
        bar = b;
        EventQueue.invokeLater(() -> {
            b.setIcon(menuBarIcon());
            b.setTooltip(tooltip);
            b.syncMenu();
        });
        return true;
    }

    /** The app icon at 44 px: shown at 22 pt, sharp on Retina menu bars. */
    private static BufferedImage menuBarIcon() {
        try (InputStream in = Notifier.class.getResourceAsStream("/app/unichat/icons/app-128.png")) {
            BufferedImage src = ImageIO.read(in);
            BufferedImage out = new BufferedImage(44, 44, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, 44, 44, null);
            g.dispose();
            return out;
        } catch (Exception e) {
            return new BufferedImage(44, 44, BufferedImage.TYPE_INT_ARGB);
        }
    }

    /** native/UnichatBar: -Dunichat.barHelper (development) or Contents/app/native/UnichatBar in the packaged app. */
    private static java.nio.file.Path barHelper() {
        String o = System.getProperty("unichat.barHelper");
        if (o != null) return java.nio.file.Path.of(o);
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null) return null;
        return java.nio.file.Path.of(launcher).getParent().resolveSibling("app").resolve("native").resolve("UnichatBar");
    }

    private void addAwtTray() {
        if (!SystemTray.isSupported()) return;
        try (InputStream in = Notifier.class.getResourceAsStream("/app/unichat/icons/tray.png")) {
            BufferedImage img = in == null ? new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB) : ImageIO.read(in);
            tray = new TrayIcon(img, tooltip, menu);
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> onOpen.accept(lastChatId));
            SystemTray.getSystemTray().add(tray);
        } catch (Exception e) {
            tray = null;
            System.err.println("Tray not available: " + e);
        }
    }

    public boolean hasTray() { return tray != null || bar != null; }

    public void notify(Chat chat, Message m) {
        Settings s = Settings.get();
        if (!s.notificationsEnabled()) return;
        lastChatId = chat.id();
        String title = chat.group() ? m.senderName() + " · " + chat.title() : chat.title();
        String body = s.notificationPreview() ? m.preview() : "Mensaje nuevo · " + chat.network().displayName();
        show(title, body, chat.id(), TrayIcon.MessageType.NONE);
        if (s.notificationSound()) awt(() -> Toolkit.getDefaultToolkit().beep());
    }

    public void reminder(Chat chat) {
        lastChatId = chat.id();
        show("Recordatorio", chat.title(), chat.id(), TrayIcon.MessageType.INFO);
    }

    /** Call on the FX thread. Windows: native balloon (clicking it fires the tray action). macOS: own card. */
    private void show(String title, String body, String chatId, TrayIcon.MessageType type) {
        if (toast != null) {
            toast.show(title, body, chatId);
            return;
        }
        awt(() -> {
            if (tray != null) tray.displayMessage(title, body, type);
        });
    }

    public void setUnread(int count) {
        awt(() -> setUnreadNow(count));
    }

    private void setUnreadNow(int count) {
        tooltip = count > 0 ? "Unichat — " + count + " sin leer" : "Unichat";
        if (tray != null) tray.setToolTip(tooltip);
        NativeBar b = bar;
        if (b != null) b.setTooltip(tooltip);
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_BADGE_NUMBER)) {
                Taskbar.getTaskbar().setIconBadge(count > 0 ? Integer.toString(count) : null);
            }
        } catch (Exception ignored) {
            // Badge is cosmetic.
        }
    }

    public void dispose() {
        disposed = true;
        NativeBar b = bar;
        bar = null;
        if (b != null) b.close();
        TrayIcon t = tray;
        tray = null;
        if (t != null) awt(() -> SystemTray.getSystemTray().remove(t));
    }

    /** AWT calls must not run on the JavaFX thread (they share the AppKit main thread on macOS). */
    private static void awt(Runnable r) {
        EventQueue.invokeLater(r);
    }
}

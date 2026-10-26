package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.settings.Settings;

import javax.imageio.ImageIO;
import java.awt.EventQueue;
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
    private volatile TrayIcon tray;
    private volatile String lastChatId;

    /** {@code onOpen} receives a chat id (or null) when the user clicks the tray icon or a notification. */
    public Notifier(Runnable onShow, Consumer<String> onOpen, Runnable onQuit) {
        if (!SystemTray.isSupported()) return;
        try (InputStream in = Notifier.class.getResourceAsStream("/app/unichat/icons/tray.png")) {
            BufferedImage img = in == null ? new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB) : ImageIO.read(in);
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Abrir Unichat");
            open.addActionListener(e -> onShow.run());
            MenuItem quit = new MenuItem("Salir");
            quit.addActionListener(e -> onQuit.run());
            menu.add(open);
            menu.addSeparator();
            menu.add(quit);
            tray = new TrayIcon(img, "Unichat", menu);
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> onOpen.accept(lastChatId));
            SystemTray.getSystemTray().add(tray);
        } catch (Exception e) {
            tray = null;
            System.err.println("Tray not available: " + e);
        }
    }

    public boolean hasTray() { return tray != null; }

    public void notify(Chat chat, Message m) {
        Settings s = Settings.get();
        if (!s.notificationsEnabled()) return;
        lastChatId = chat.id();
        String title = chat.group() ? m.senderName() + " · " + chat.title() : chat.title();
        String body = s.notificationPreview() ? m.preview() : "Mensaje nuevo · " + chat.network().displayName();
        awt(() -> {
            if (tray != null) tray.displayMessage(title, body, TrayIcon.MessageType.NONE);
            if (s.notificationSound()) Toolkit.getDefaultToolkit().beep();
        });
    }

    public void reminder(Chat chat) {
        lastChatId = chat.id();
        awt(() -> {
            if (tray != null) tray.displayMessage("Recordatorio", chat.title(), TrayIcon.MessageType.INFO);
        });
    }

    public void setUnread(int count) {
        awt(() -> setUnreadNow(count));
    }

    private void setUnreadNow(int count) {
        if (tray != null) tray.setToolTip(count > 0 ? "Unichat — " + count + " sin leer" : "Unichat");
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_BADGE_NUMBER)) {
                Taskbar.getTaskbar().setIconBadge(count > 0 ? Integer.toString(count) : null);
            }
        } catch (Exception ignored) {
            // Badge is cosmetic.
        }
    }

    public void dispose() {
        TrayIcon t = tray;
        tray = null;
        if (t != null) awt(() -> SystemTray.getSystemTray().remove(t));
    }

    /** AWT calls must not run on the JavaFX thread (they share the AppKit main thread on macOS). */
    private static void awt(Runnable r) {
        EventQueue.invokeLater(r);
    }
}

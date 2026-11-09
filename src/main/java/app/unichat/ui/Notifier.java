package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.support.Os;

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
    private static final boolean MAC = Os.mac();

    private volatile TrayIcon tray;
    private volatile String lastChatId;
    private final DesktopToast toast;

    /**
     * {@code onOpen} receives a chat id (or null) when the user clicks the tray icon or a notification; {@code onHide}
     * puts the window away in the tray. The menu, as Telegram's: the window (open or put away), notifications on or off, quit.
     */
    public Notifier(Runnable onShow, Runnable onHide, Consumer<String> onOpen, Runnable onQuit) {
        toast = MAC ? new DesktopToast(onOpen) : null;
        this.onOpen = onOpen;
        this.onShow = onShow;
        if (GraphicsEnvironment.isHeadless()) { // tests, snapshot runs: no tray, and AWT menus can't be built
            this.menu = null;
            return;
        }
        PopupMenu menu = new PopupMenu();
        MenuItem window = new MenuItem();
        window.addActionListener(e -> (windowShown ? onHide : onShow).run());
        MenuItem notifications = new MenuItem();
        notifications.addActionListener(e -> {
            Settings.get().setNotificationsEnabled(!Settings.get().notificationsEnabled());
            relabel();
        });
        MenuItem quit = new MenuItem();
        quit.addActionListener(e -> onQuit.run());
        menu.add(window);
        menu.add(notifications);
        menu.add(quit);
        this.menu = menu;
        current = this;
        labelNow();
        // macOS: the native item (narrow, real menu, like Apple's own); the AWT TrayIcon if it's missing or dies.
        if (MAC && startNativeBar()) return;
        addAwtTray();
    }

    private static final int BADGED_SIZE = 32;
    private static final int BADGE_HEIGHT = 20;

    private final PopupMenu menu;
    private final Consumer<String> onOpen;
    private final Runnable onShow;
    private volatile NativeBar bar;
    private volatile String tooltip = "Unichat";
    private volatile int unread;
    private BufferedImage trayIcon;
    private volatile boolean disposed;
    private volatile boolean windowShown;
    private volatile boolean windowFocused;
    /** The one in use, for Ajustes to reach; null in tests without a display. */
    private static volatile Notifier current;

    /** The tray and menu-bar menu in the current language and state. Any thread. */
    public void relabel() {
        if (menu == null) return;
        EventQueue.invokeLater(() -> {
            labelNow();
            NativeBar b = bar;
            if (b != null) b.syncMenu();
        });
    }

    /** Notifications were turned on or off elsewhere (Ajustes): the menu says the other one. Any thread. */
    static void notificationsChanged() {
        Notifier n = current;
        if (n != null) n.relabel();
    }

    private void labelNow() {
        menu.getItem(0).setLabel(I18n.t(windowShown ? "notifier.hide" : "notifier.open"));
        menu.getItem(1).setLabel(I18n.t(Settings.get().notificationsEnabled() ? "notifier.mute" : "notifier.unmute"));
        menu.getItem(2).setLabel(I18n.t("notifier.quit"));
    }

    /**
     * Whether the window is open on screen (not hidden in the tray nor minimised). While it is and has the focus, a
     * click on the menu-bar icon opens the menu, as Telegram's does; otherwise it brings the window. Any thread.
     */
    public void setWindowShown(boolean shown) {
        if (shown == windowShown) return;
        windowShown = shown;
        syncLeftClick();
        relabel();
    }

    /** Whether one of Unichat's windows has the focus: behind other apps, a click brings it to the front. Any thread. */
    public void setWindowFocused(boolean focused) {
        if (focused == windowFocused) return;
        windowFocused = focused;
        syncLeftClick();
    }

    private void syncLeftClick() {
        NativeBar b = bar;
        if (b != null) b.setLeftClickMenu(windowShown && windowFocused);
    }

    private boolean startNativeBar() {
        NativeBar b = NativeBar.start(barHelper(), menu, () -> EventQueue.invokeLater(() -> {
            bar = null;
            if (tray == null && !disposed) addAwtTray(); // the helper died: keep a tray icon
        }), onOpen, onShow);
        if (b == null) return false;
        bar = b;
        b.setGlobalShortcut(app.unichat.settings.Settings.get().globalShortcut());
        b.setLeftClickMenu(windowShown && windowFocused);
        EventQueue.invokeLater(() -> {
            b.setTooltip(tooltip);
            b.setCount(unread);
            b.syncMenu();
        });
        return true;
    }

    /**
     * The helper's executable: -Dunichat.barHelper (development) or Contents/app/native/UnichatBar.app in the packaged
     * app (run from inside its bundle, which notifications need).
     */
    private static java.nio.file.Path barHelper() {
        String o = System.getProperty("unichat.barHelper");
        if (o != null) return java.nio.file.Path.of(o);
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null) return null;
        return java.nio.file.Path.of(launcher).getParent().resolveSibling("app").resolve("native")
                .resolve("UnichatBar.app").resolve("Contents").resolve("MacOS").resolve("UnichatBar");
    }

    private void addAwtTray() {
        if (!SystemTray.isSupported()) return;
        try (InputStream in = Notifier.class.getResourceAsStream("/app/unichat/icons/tray.png")) {
            BufferedImage img = in == null ? new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB) : ImageIO.read(in);
            trayIcon = img;
            tray = new TrayIcon(withCount(img, unread), tooltip, menu);
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> onOpen.accept(lastChatId));
            tray.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    // Windows opens the menu with the right button, and on a plain click would do nothing.
                    if (e.getButton() == java.awt.event.MouseEvent.BUTTON1 && e.getClickCount() == 1) onShow.run();
                }
            });
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
        String body = s.notificationPreview() ? m.preview() : I18n.t("notifier.newMessage", chat.network().displayName());
        NativeBar b = bar;
        if (b != null && Boolean.TRUE.equals(b.notificationsAllowed())) {
            // macOS's own notification: never takes the keyboard, follows Do Not Disturb and Focus.
            b.notify(chat.id(), title, body, s.notificationSound());
            return;
        }
        show(title, body, chat.id(), TrayIcon.MessageType.NONE);
        if (s.notificationSound()) awt(() -> Toolkit.getDefaultToolkit().beep());
    }

    public void reminder(Chat chat) {
        lastChatId = chat.id();
        NativeBar b = bar;
        if (b != null && Boolean.TRUE.equals(b.notificationsAllowed())) {
            b.notify(chat.id(), I18n.t("notifier.reminder"), chat.title(), Settings.get().notificationSound());
            return;
        }
        show(I18n.t("notifier.reminder"), chat.title(), chat.id(), TrayIcon.MessageType.INFO);
    }

    /** {@code chatId} was read: its notifications leave Notification Center (macOS). */
    public void clear(String chatId) {
        NativeBar b = bar;
        if (b != null && chatId != null) b.clear(chatId);
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
        boolean changed = count != unread;
        unread = count;
        tooltip = count > 0 ? I18n.t("notifier.tooltipUnread", count) : "Unichat";
        if (tray != null) {
            tray.setToolTip(tooltip);
            if (changed) tray.setImage(withCount(trayIcon, count));
        }
        NativeBar b = bar;
        if (b != null) {
            b.setTooltip(tooltip);
            if (changed) b.setCount(count);
        }
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_BADGE_NUMBER)) {
                Taskbar.getTaskbar().setIconBadge(count > 0 ? Integer.toString(count) : null);
            }
        } catch (Exception ignored) {
            // Badge is cosmetic.
        }
    }

    /** The tray icon with the count in a corner, as a Windows notification area shows it: no text fits beside it. */
    static BufferedImage withCount(BufferedImage icon, int count) {
        if (count <= 0) return icon;
        BufferedImage out = new BufferedImage(BADGED_SIZE, BADGED_SIZE, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(icon, 0, 0, BADGED_SIZE, BADGED_SIZE, null);
        String text = Formats.badge(count);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, text.length() > 2 ? 15 : 19));
        java.awt.FontMetrics fm = g.getFontMetrics();
        int w = Math.max(BADGE_HEIGHT, fm.stringWidth(text) + 6);
        int x = BADGED_SIZE - w;
        int y = BADGED_SIZE - BADGE_HEIGHT;
        g.setColor(new java.awt.Color(0xD92D20));
        g.fillRoundRect(x, y, w, BADGE_HEIGHT, BADGE_HEIGHT, BADGE_HEIGHT);
        g.setColor(java.awt.Color.WHITE);
        g.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + (BADGE_HEIGHT + fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
        return out;
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

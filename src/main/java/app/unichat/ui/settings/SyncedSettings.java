package app.unichat.ui.settings;

import app.unichat.ui.desktop.DockIcon;
import app.unichat.ui.desktop.NativeBar;
import app.unichat.ui.desktop.Notifier;
import app.unichat.ui.timeline.AiReplyButton;
import app.unichat.assist.AiSettings;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.update.UpdateChecker;

/**
 * Settings that changed from outside while Unichat runs (another computer, through the cloud), applied at once, as if
 * chosen in Ajustes: most are read when used and need nothing; these were read once, and get what Ajustes does for
 * each. The language last, since it rebuilds the window. Discord's photo cleanup runs when the server starts.
 */
public final class SyncedSettings {
    private SyncedSettings() {}

    /** On the UI thread. {@code redraw}: the window, as Ajustes redraws it (theme, lists, the open chat). */
    public static void apply(Runnable redraw) {
        AiReplyButton.SHOWN.set(AiSettings.enabled());
        NativeBar.applyGlobalShortcut(Settings.get().globalShortcut());
        DockIcon.settingChanged();
        Notifier.notificationsChanged();
        UpdateChecker.get().settingChanged();
        redraw.run();
        if (SettingsWindow.current() != null) SettingsWindow.select(SettingsWindow.currentPage()); // its controls show the new values
        I18n.use(System.getProperty("unichat.language", Settings.get().language()));
    }
}

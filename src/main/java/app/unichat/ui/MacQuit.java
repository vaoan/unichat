package app.unichat.ui;

import com.sun.glass.ui.Application;

import java.util.Map;

/**
 * macOS: JavaFX turns "Salir" (⌘Q, app menu, Dock, logging out) into "close every window", and closing the window
 * only hides Unichat in the tray, so it never quit. This wraps JavaFX's own glass event handler: the quit action
 * runs {@code onQuit}, everything else goes to the original handler. Internal JavaFX API (reachable because
 * JavaFX is on the classpath); if it changes, Unichat keeps the old behaviour instead of failing.
 */
public final class MacQuit {
    private MacQuit() {}

    /**
     * Call on the FX thread after the toolkit started. No-op off macOS.
     *
     * @param onActive the app was activated: the Dock icon clicked, or the app opened again
     */
    public static void install(Runnable onQuit, Runnable onActive) {
        if (!MainView.MAC) return;
        try {
            Application app = Application.GetApplication();
            Application.EventHandler original = app.getEventHandler();
            if (original == null) return;
            app.setEventHandler(new Application.EventHandler() {
                @Override public void handleQuitAction(Application a, long time) { onQuit.run(); }

                @Override public void handleWillFinishLaunchingAction(Application a, long t) { original.handleWillFinishLaunchingAction(a, t); }
                @Override public void handleDidFinishLaunchingAction(Application a, long t) { original.handleDidFinishLaunchingAction(a, t); }
                @Override public void handleWillBecomeActiveAction(Application a, long t) { original.handleWillBecomeActiveAction(a, t); }
                @Override public void handleDidBecomeActiveAction(Application a, long t) {
                    original.handleDidBecomeActiveAction(a, t);
                    onActive.run();
                }
                @Override public void handleWillResignActiveAction(Application a, long t) { original.handleWillResignActiveAction(a, t); }
                @Override public void handleDidResignActiveAction(Application a, long t) { original.handleDidResignActiveAction(a, t); }
                @Override public void handleDidReceiveMemoryWarning(Application a, long t) { original.handleDidReceiveMemoryWarning(a, t); }
                @Override public void handleWillHideAction(Application a, long t) { original.handleWillHideAction(a, t); }
                @Override public void handleDidHideAction(Application a, long t) { original.handleDidHideAction(a, t); }
                @Override public void handleWillUnhideAction(Application a, long t) { original.handleWillUnhideAction(a, t); }
                @Override public void handleDidUnhideAction(Application a, long t) { original.handleDidUnhideAction(a, t); }
                @Override public void handleOpenFilesAction(Application a, long t, String[] files) { original.handleOpenFilesAction(a, t, files); }
                @Override public void handlePreferencesChanged(Map<String, Object> prefs) { original.handlePreferencesChanged(prefs); }
            });
        } catch (Throwable e) {
            System.err.println("macOS quit handler unavailable: " + e);
        }
    }
}

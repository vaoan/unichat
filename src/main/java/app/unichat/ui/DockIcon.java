package app.unichat.ui;

import app.unichat.settings.Settings;
import app.unichat.support.Os;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

/**
 * macOS only: by default Unichat lives in the menu bar and has no Dock icon at all (the package declares LSUIElement, so
 * it isn't there at launch nor among the Dock's recent apps); Ajustes can show one while the window is open, by switching
 * NSApplication's activation policy (regular ↔ accessory). Java has no API for this, so it goes
 * through the Objective-C runtime with JNA. On other systems every call is a no-op: a hidden window already
 * leaves the Windows taskbar. Call on the FX thread, which on macOS is AppKit's main thread.
 */
public final class DockIcon {
    private static final long POLICY_REGULAR = 0, POLICY_ACCESSORY = 1;
    private static final boolean MAC = Os.mac();
    private static volatile boolean broken;
    private static volatile boolean trayAvailable = true;

    private DockIcon() {}

    /** Whether the menu bar icon exists; without it the Dock is the only way back to a hidden window. */
    public static void setTrayAvailable(boolean available) { trayAvailable = available; }

    /** Whether the Dock icon goes with the window: the person's choice, unless it's the only way in. */
    static boolean wanted(boolean setting, boolean tray) { return setting || !tray; }

    /**
     * The Dock icon also comes while a photo, video or settings window is open, so ⌘Tab reaches Unichat (macOS lists only
     * apps with one), and App Exposé shows its windows.
     */
    private static volatile boolean otherWindows;
    private static volatile boolean mainShowing;

    /** Whether there's a Dock icon: with any window besides the main one, or with the main one if wanted. */
    static boolean regular(boolean main, boolean others, boolean setting, boolean tray) {
        return others || main && wanted(setting, tray);
    }

    /** Window about to be shown (or the setting changed while it is): the Dock icon if wanted, and the active app. */
    public static void show() {
        mainShowing = true;
        apply();
        send("activateIgnoringOtherApps:", 1L);
    }

    /** Window hidden: remove the Dock icon unless another window is open; the menu bar icon stays. */
    public static void hide() {
        mainShowing = false;
        apply();
        // Otherwise it stays the active app, and a click on the Dock icon wouldn't activate it: nothing would happen.
        if (!otherWindows) send("deactivate", 0L);
    }

    /** Makes Unichat the active app (macOS), as a click on its window would; without it, its open menu can't be closed. */
    static void activate() {
        send("activateIgnoringOtherApps:", 1L);
    }

    /** "Mostrar en el Dock" changed elsewhere (the cloud): the icon follows, without bringing Unichat to the front. */
    static void settingChanged() {
        apply();
    }

    /** A window besides the main one opened, or the last of them closed. */
    static void setOtherWindows(boolean open) {
        if (open == otherWindows) return;
        otherWindows = open;
        apply();
    }

    private static void apply() {
        setPolicy(regular(mainShowing, otherWindows, Settings.get().showInDock(), trayAvailable) ? POLICY_REGULAR : POLICY_ACCESSORY);
    }

    private static boolean setPolicy(long policy) {
        return send("setActivationPolicy:", policy);
    }

    private static boolean send(String selector, long arg) {
        if (!MAC || broken) return false;
        try {
            NativeLibrary objc = NativeLibrary.getInstance("objc");
            Function getClass = objc.getFunction("objc_getClass");
            Function sel = objc.getFunction("sel_registerName");
            Function msg = objc.getFunction("objc_msgSend");
            Pointer cls = getClass.invokePointer(new Object[]{"NSApplication"});
            Pointer app = msg.invokePointer(new Object[]{cls, sel.invokePointer(new Object[]{"sharedApplication"})});
            msg.invoke(Void.class, new Object[]{app, sel.invokePointer(new Object[]{selector}), arg});
            return true;
        } catch (Throwable e) {
            // Never break the window over a cosmetic Dock detail.
            broken = true;
            System.err.println("Dock icon toggle unavailable: " + e);
            return false;
        }
    }
}

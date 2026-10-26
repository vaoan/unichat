package app.unichat.ui;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

/**
 * macOS only: shows the Dock icon while the window is open and removes it while Unichat lives in the menu bar,
 * by switching NSApplication's activation policy (regular ↔ accessory). Java has no API for this, so it goes
 * through the Objective-C runtime with JNA. On other systems every call is a no-op: a hidden window already
 * leaves the Windows taskbar. Call on the FX thread, which on macOS is AppKit's main thread.
 */
public final class DockIcon {
    private static final long POLICY_REGULAR = 0, POLICY_ACCESSORY = 1;
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    private static volatile boolean broken;

    private DockIcon() {}

    /** Window about to be shown: bring the Dock icon back and make Unichat the active app. */
    public static void show() {
        if (setPolicy(POLICY_REGULAR)) send("activateIgnoringOtherApps:", 1L);
    }

    /** Window hidden: remove the Dock icon; the menu bar icon stays. */
    public static void hide() {
        setPolicy(POLICY_ACCESSORY);
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

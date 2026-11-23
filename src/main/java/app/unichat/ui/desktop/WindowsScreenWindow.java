package app.unichat.ui.desktop;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The message-only window {@link WindowsScreen} listens with: made, registered for session changes and the display's
 * power setting, and run on the watch's one thread. The few Win32 calls it needs are bound here with plain JNA (no
 * jna-platform: it lacks RegisterPowerSettingNotification anyway). Nothing native loads until {@link #open}, so the
 * class is safe to touch on any OS.
 */
final class WindowsScreenWindow implements WindowsScreen.MessageWindow {
    private static final Pointer HWND_MESSAGE = new Pointer(-3);
    private static final int NOTIFY_FOR_THIS_SESSION = 0;
    private static final int DEVICE_NOTIFY_WINDOW_HANDLE = 0;
    private static final int WM_DESTROY = 0x0002;
    private static final int WM_CLOSE = 0x0010;
    /** GUID_CONSOLE_DISPLAY_STATE, 6FE69556-704A-47A0-8F24-C28D936FDA47, as it lies in memory. */
    private static final String DISPLAY_GUID = "5695e66f4a70a0478f24c28d936fda47";
    private static final int SETTING_LENGTH = 16;
    private static final int SETTING_DATA = 20;
    private static final AtomicInteger CLASSES = new AtomicInteger();

    /** Kept in a field: a callback JNA hands to Windows must outlive every message, or it's collected under it. */
    private final WindowProc proc = new WindowProc() {
        @Override
        public Pointer callback(Pointer hwnd, int msg, Pointer wParam, Pointer lParam) {
            return message(hwnd, msg, wParam, lParam);
        }
    };
    private final WString className = new WString("UnichatScreen" + CLASSES.incrementAndGet());
    private final Memory displayGuid = guidMemory();
    private WindowsScreen.Handler handler;
    private Pointer instance;
    private boolean classRegistered;
    private volatile Pointer hwnd;
    private boolean sessionRegistered;
    private Pointer powerNotification;

    @Override
    public void open(WindowsScreen.Handler onMessage) {
        handler = onMessage;
        try {
            instance = Kernel32.LIB.GetModuleHandleW(null);
            WndClassEx c = new WndClassEx();
            c.lpfnWndProc = proc;
            c.hInstance = instance;
            c.lpszClassName = className;
            c.cbSize = c.size();
            classRegistered = User32.LIB.RegisterClassExW(c) != 0;
            check(classRegistered, "RegisterClassExW");
            hwnd = User32.LIB.CreateWindowExW(0, className, className, 0, 0, 0, 0, 0, HWND_MESSAGE, null, instance, null);
            check(hwnd != null, "CreateWindowExW");
            sessionRegistered = Wtsapi32.LIB.WTSRegisterSessionNotification(hwnd, NOTIFY_FOR_THIS_SESSION);
            check(sessionRegistered, "WTSRegisterSessionNotification");
            powerNotification = User32.LIB.RegisterPowerSettingNotification(hwnd, displayGuid, DEVICE_NOTIFY_WINDOW_HANDLE);
            check(powerNotification != null, "RegisterPowerSettingNotification");
        } catch (RuntimeException | LinkageError e) {
            unregister();
            if (hwnd != null) User32.LIB.DestroyWindow(hwnd);
            hwnd = null;
            unregisterClass();
            throw e;
        }
    }

    @Override
    public void run() {
        Memory msg = new Memory(64); // a MSG is 48 bytes on 64-bit Windows; only Windows reads it
        while (User32.LIB.GetMessageW(msg, null, 0, 0) > 0) User32.LIB.DispatchMessageW(msg);
        unregisterClass();
    }

    @Override
    public void close() {
        Pointer h = hwnd;
        if (h != null) User32.LIB.PostMessageW(h, WM_CLOSE, null, null);
    }

    private Pointer message(Pointer window, int msg, Pointer wParam, Pointer lParam) {
        if (msg == WM_CLOSE) {
            unregister();
            User32.LIB.DestroyWindow(window);
            return null;
        }
        if (msg == WM_DESTROY) {
            hwnd = null;
            User32.LIB.PostQuitMessage(0);
            return null;
        }
        long w = value(wParam);
        int display = msg == ScreenMessages.WM_POWERBROADCAST && w == ScreenMessages.PBT_POWERSETTINGCHANGE
                ? displayState(lParam) : ScreenMessages.NO_DISPLAY_STATE;
        try {
            handler.message(msg, w, display);
        } catch (RuntimeException e) {
            System.err.println("Screen lock and display watch: a message failed: " + e);
        }
        return User32.LIB.DefWindowProcW(window, msg, wParam, lParam);
    }

    private void unregister() {
        if (powerNotification != null) User32.LIB.UnregisterPowerSettingNotification(powerNotification);
        powerNotification = null;
        if (sessionRegistered) Wtsapi32.LIB.WTSUnRegisterSessionNotification(hwnd);
        sessionRegistered = false;
    }

    private void unregisterClass() {
        if (classRegistered) User32.LIB.UnregisterClassW(className, instance);
        classRegistered = false;
    }

    private static void check(boolean ok, String call) {
        if (!ok) throw new IllegalStateException(call + " failed (error " + Native.getLastError() + ")");
    }

    private static long value(Pointer p) {
        return p == null ? 0 : Pointer.nativeValue(p);
    }

    /**
     * The data of the POWERBROADCAST_SETTING at {@code setting} (its GUID, its DataLength, then a DWORD), when it is the
     * display's state; {@link ScreenMessages#NO_DISPLAY_STATE} otherwise.
     */
    static int displayState(Pointer setting) {
        if (setting == null || !Arrays.equals(setting.getByteArray(0, SETTING_LENGTH), displayGuid())) {
            return ScreenMessages.NO_DISPLAY_STATE;
        }
        return setting.getInt(SETTING_LENGTH) >= Integer.BYTES ? setting.getInt(SETTING_DATA) : ScreenMessages.NO_DISPLAY_STATE;
    }

    static byte[] displayGuid() {
        return HexFormat.of().parseHex(DISPLAY_GUID);
    }

    private static Memory guidMemory() {
        Memory m = new Memory(SETTING_LENGTH);
        m.write(0, displayGuid(), 0, SETTING_LENGTH);
        return m;
    }

    /** LRESULT (CALLBACK *WNDPROC)(HWND, UINT, WPARAM, LPARAM): the pointer-sized ones as pointers. */
    interface WindowProc extends StdCallLibrary.StdCallCallback {
        Pointer callback(Pointer hwnd, int msg, Pointer wParam, Pointer lParam);
    }

    /** WNDCLASSEXW. */
    @Structure.FieldOrder({"cbSize", "style", "lpfnWndProc", "cbClsExtra", "cbWndExtra", "hInstance", "hIcon",
        "hCursor", "hbrBackground", "lpszMenuName", "lpszClassName", "hIconSm"})
    public static final class WndClassEx extends Structure {
        public int cbSize;
        public int style;
        public WindowProc lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public Pointer hInstance;
        public Pointer hIcon;
        public Pointer hCursor;
        public Pointer hbrBackground;
        public WString lpszMenuName;
        public WString lpszClassName;
        public Pointer hIconSm;
    }

    interface User32 extends StdCallLibrary {
        User32 LIB = Native.load("user32", User32.class);

        short RegisterClassExW(WndClassEx c);

        boolean UnregisterClassW(WString name, Pointer instance);

        Pointer CreateWindowExW(int exStyle, WString cls, WString name, int style, int x, int y, int width, int height,
                Pointer parent, Pointer menu, Pointer instance, Pointer param);

        boolean DestroyWindow(Pointer hwnd);

        Pointer DefWindowProcW(Pointer hwnd, int msg, Pointer wParam, Pointer lParam);

        int GetMessageW(Pointer msg, Pointer hwnd, int min, int max);

        Pointer DispatchMessageW(Pointer msg);

        boolean PostMessageW(Pointer hwnd, int msg, Pointer wParam, Pointer lParam);

        void PostQuitMessage(int code);

        Pointer RegisterPowerSettingNotification(Pointer recipient, Pointer guid, int flags);

        boolean UnregisterPowerSettingNotification(Pointer handle);
    }

    interface Kernel32 extends StdCallLibrary {
        Kernel32 LIB = Native.load("kernel32", Kernel32.class);

        Pointer GetModuleHandleW(WString name);
    }

    interface Wtsapi32 extends StdCallLibrary {
        Wtsapi32 LIB = Native.load("wtsapi32", Wtsapi32.class);

        boolean WTSRegisterSessionNotification(Pointer hwnd, int flags);

        boolean WTSUnRegisterSessionNotification(Pointer hwnd);
    }
}

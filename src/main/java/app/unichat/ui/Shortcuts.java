package app.unichat.ui;

import app.unichat.support.Os;

/** How shortcuts are written on screen. */
final class Shortcuts {
    private Shortcuts() {}

    /** {@code "K"}: "⌘K" on a Mac, "Ctrl+K" everywhere else. */
    static String key(String key) {
        return (Os.mac() ? "⌘" : "Ctrl+") + key;
    }

    /** Text size: bigger, smaller, back to normal. */
    static String zoomKeys() {
        return key("+") + " / " + key("−") + " / " + key("0");
    }
}

package app.unichat.ui;

import app.unichat.support.Os;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShortcutsTest {
    @Test
    void onlyAMacHasACommandKey() {
        assertEquals(Os.mac() ? "⌘K" : "Ctrl+K", Shortcuts.key("K"));
        assertEquals(Os.mac() ? "⌘Shift+U" : "Ctrl+Shift+U", Shortcuts.key("Shift+U"));
    }
}

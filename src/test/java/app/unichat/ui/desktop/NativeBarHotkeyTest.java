package app.unichat.ui.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import app.unichat.Present;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledOnOs(OS.WINDOWS)
class NativeBarHotkeyTest {
    @TempDir Path dir;

    @SuppressWarnings("NullAway") // headless: no AWT menu can be made, and this helper is never asked for one
    @Test
    void theHelpersShortcutBringsTheWindowAndItIsToldWhetherItsOn() throws Exception {
        Path told = dir.resolve("recibido.txt");
        // Records what it's told, then reports the key: stands in for UnichatBar.
        Path helper = Files.writeString(dir.resolve("helper"), "#!/bin/sh\nread a; echo \"$a\" > '" + told + "'\necho hotkey\ncat >/dev/null\n");
        assertTrue(helper.toFile().setExecutable(true));
        CountDownLatch pressed = new CountDownLatch(1);
        NativeBar bar = Present.of(NativeBar.start(helper, null, () -> { }, id -> { }, pressed::countDown));
        try {
            bar.setGlobalShortcut(true);
            pressed.await();
            assertEquals("hotkey\t1", Files.readString(told).strip());
        } finally {
            bar.close();
        }
    }

    @SuppressWarnings("NullAway") // headless: no AWT menu can be made, and this helper is never asked for one
    @Test
    void aLeftClickOnTheIconBringsTheWindow() throws Exception {
        Path helper = Files.writeString(dir.resolve("helper"), "#!/bin/sh\necho show\ncat >/dev/null\n");
        assertTrue(helper.toFile().setExecutable(true));
        CountDownLatch shown = new CountDownLatch(1);
        NativeBar bar = Present.of(NativeBar.start(helper, null, () -> { }, id -> { }, shown::countDown));
        try {
            shown.await();
        } finally {
            bar.close();
        }
    }
}

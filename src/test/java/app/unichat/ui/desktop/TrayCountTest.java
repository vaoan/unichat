package app.unichat.ui.desktop;

import app.unichat.ui.Formats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayCountTest {
    @TempDir Path dir;

    @Test
    void theIconCarriesTheCountOnlyWhenThereIsOne() {
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        assertSame(icon, Notifier.withCount(icon, 0));
        BufferedImage one = Notifier.withCount(icon, 3);
        BufferedImage many = Notifier.withCount(icon, 12345);
        assertTrue((one.getRGB(one.getWidth() - 3, one.getHeight() - 10) >>> 24) > 0, "a badge in the corner");
        assertTrue((many.getRGB(0, 0) >>> 24) == 0, "the rest stays the icon");
        assertEquals("99+", Formats.badge(12345));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theHelperIsToldTheCountAndWhenThereIsNone() throws Exception {
        // A pipe, not a file: each line is read the moment the helper passes it on, and its end says it has no more.
        Path told = dir.resolve("recibido");
        assertEquals(0, new ProcessBuilder("mkfifo", told.toString()).inheritIO().start().waitFor(), "mkfifo");
        Path helper = Files.writeString(dir.resolve("helper"),
                "#!/bin/sh\nexec 3>'" + told + "'\nwhile IFS= read -r a; do echo \"$a\" >&3; done\n");
        assertTrue(helper.toFile().setExecutable(true));
        NativeBar bar = NativeBar.start(helper, null, () -> { }, id -> { }, () -> { });
        try (BufferedReader received = Files.newBufferedReader(told)) {
            bar.setCount(3);
            bar.setCount(150);
            bar.setCount(0);
            assertEquals(List.of("count\t3", "count\t99+", "count\t"),
                    List.of(received.readLine(), received.readLine(), received.readLine()));
            bar.close();
            assertNull(received.readLine(), "nothing else");
        } finally {
            bar.close();
        }
    }
}

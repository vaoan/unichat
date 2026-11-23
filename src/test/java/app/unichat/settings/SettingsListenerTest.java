package app.unichat.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Whoever follows a setting hears it change, whether it changed here or came from the cloud. */
class SettingsListenerTest {
    @TempDir Path dir;
    private Settings s;

    @AfterEach
    void writesEnd() {
        if (s != null) s.flush();
    }

    @Test
    void aChangedKeyIsTold() {
        s = new Settings(dir);
        List<String> heard = new ArrayList<>();
        Consumer<String> l = heard::add;
        s.addListener(l);
        s.setNotifyArchived(true);
        s.removeListener(l);
        s.setNotifyArchived(false);
        assertEquals(List.of(Settings.NOTIFY_ARCHIVED), heard, "heard once, until it stopped listening");
    }

    @Test
    void settingsFromTheCloudAreToldAsEverything() {
        s = new Settings(dir);
        List<String> heard = new ArrayList<>();
        s.addListener(heard::add);
        s.replacePortable(Map.of(Settings.NOTIFY_ARCHIVED, "true"), k -> false);
        assertEquals(Arrays.asList((String) null), heard, "null: any key may have changed");
    }
}

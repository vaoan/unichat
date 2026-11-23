package app.unichat.cloud;

import app.unichat.settings.Settings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;

/** Disconnecting the account. */
class CloudAccountTest {
    private static final List<String> ACCOUNT = List.of("cloud.service", "cloud.url", "cloud.user", "cloud.secret", "cloud.settingsBase");

    @Test
    void disconnectingForgetsTheVaultHeldOrSeenHere() {
        Map<String, String> were = new HashMap<>();
        for (String k : ACCOUNT) were.put(k, Settings.get().getString(k, null));
        try {
            for (String k : NoVaultHeld.KEYS) Settings.get().set(k, "x");
            CloudAccount.forget();
            // Another account connected next has another vault, or none: what this one held would keep it MISSING.
            for (String k : NoVaultHeld.KEYS) assertNull(Settings.get().getString(k, null), k);
        } finally {
            were.forEach(Settings.get()::set);
        }
    }

    @Test
    void disconnectingForgetsThatTheOldNamesWereMovedHere() {
        Map<String, String> were = new HashMap<>();
        for (String k : ACCOUNT) were.put(k, Settings.get().getString(k, null));
        String done = Settings.get().getString(CloudNames.DONE, null);
        try {
            Settings.get().setBool(CloudNames.DONE, true);
            CloudAccount.forget();
            assertNull(Settings.get().getString(CloudNames.DONE, null), "another account may still have the old names");
        } finally {
            were.forEach(Settings.get()::set);
            Settings.get().set(CloudNames.DONE, done);
        }
    }
}

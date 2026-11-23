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
        List<String> keys = List.of(CloudNames.DONE, CloudNames.KEPT, VaultNames.LEFT);
        for (String k : keys) were.put(k, Settings.get().getString(k, null));
        try {
            for (String k : keys) Settings.get().set(k, "x");
            CloudAccount.forget();
            for (String k : keys) assertNull(Settings.get().getString(k, null), "another account may still have the old names: " + k);
        } finally {
            were.forEach(Settings.get()::set);
        }
    }
}

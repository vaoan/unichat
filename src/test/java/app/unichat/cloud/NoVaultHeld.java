package app.unichat.cloud;

import app.unichat.settings.Settings;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;

/**
 * Every test starts and ends with no vault held here (registered for all, `META-INF/services`): every
 * {@code cloud.vault.} key of the test data folder cleared. A run killed halfway left a vault's id and key there, and the
 * next run's computers found it MISSING instead of going plain ({@code CloudTextsTest}). A test that holds a vault sets
 * its keys itself ({@link VaultComputer}, {@link VaultFixture}).
 */
public final class NoVaultHeld implements BeforeEachCallback, AfterEachCallback {
    /** The vault's keys on this computer: {@link VaultComputer#KEYS}' {@code cloud.vault.} ones, every one the app keeps ({@code NoVaultHeldTest}). */
    static final List<String> KEYS = VaultComputer.KEYS.stream().filter(k -> k.startsWith("cloud.vault.")).toList();

    @Override
    public void beforeEach(ExtensionContext context) {
        clear();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        clear();
    }

    private static void clear() {
        Settings s = Settings.get();
        for (String k : KEYS) if (s.getString(k, null) != null) s.set(k, null);
    }
}

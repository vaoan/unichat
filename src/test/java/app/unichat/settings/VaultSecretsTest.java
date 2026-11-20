package app.unichat.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The keys that are the person's, not a computer's: what the vault carries of the settings, and nothing else. */
class VaultSecretsTest {
    @TempDir
    Path dir;
    private Settings settings;

    @AfterEach
    void done() {
        if (settings != null) settings.flush();
    }

    private Settings settings() {
        settings = new Settings(dir);
        return settings;
    }

    /** What never comes from the vault: what runs here, this computer's paths and sessions, the way to the vault. */
    private static boolean never(String key) {
        return (key.startsWith("cloud.") && !key.equals("cloud.dropboxAppKey") && !key.equals("cloud.pcloudClientId"))
            || key.equals("stt.command") || key.equals("stt.engine") || key.startsWith("autosave.") || key.equals("ai.folder")
            || key.startsWith("window.") || key.startsWith("app.update.") || key.startsWith("matrix.") || key.startsWith("session.")
            || (key.startsWith("server.") && !key.equals("server.telegram.apiId") && !key.equals("server.telegram.apiHash"));
    }

    @Test
    void nothingThatRunsOrIsAPathHereComesFromTheVault() {
        assertEquals(java.util.Set.of("server.telegram.apiId", "server.telegram.apiHash", "cloud.dropboxAppKey", "cloud.pcloudClientId"),
            Settings.VAULT_SECRETS);
        for (String k : Settings.VAULT_SECRETS) assertFalse(never(k), k);
        Settings s = settings();
        s.set("stt.command", "say hola");
        s.set("autosave.x", "/Users/ana/Fotos");
        s.set("cloud.secret", "token-de-aqui");
        Map<String, String> theirs = new TreeMap<>();
        theirs.put("stt.command", "rm -rf ~");
        theirs.put("stt.engine", "COMMAND");
        theirs.put("autosave.x", "/");
        theirs.put("cloud.secret", "token-de-otro");
        theirs.put("matrix.accessToken", "otro");
        theirs.put("ui.theme", "DARK");
        theirs.put("gif.a.b.key", "x");
        theirs.put("gif..key", "x");
        theirs.put("server.telegram.apiId", "12345");
        theirs.put("gif.giphy.key", "clave");
        s.adoptVaultSecrets(theirs);
        assertEquals("say hola", s.getString("stt.command", null));
        assertNull(s.getString("stt.engine", null));
        assertEquals("/Users/ana/Fotos", s.getString("autosave.x", null));
        assertEquals("token-de-aqui", s.getString("cloud.secret", null));
        assertNull(s.getString("matrix.accessToken", null));
        assertNull(s.getString("ui.theme", null), "the settings travel in their own part");
        assertNull(s.getString("gif.a.b.key", null));
        assertNull(s.getString("gif..key", null));
        assertEquals("12345", s.getString("server.telegram.apiId", null), "the person's keys are taken");
        assertEquals("clave", s.getString("gif.giphy.key", null));
    }

    @Test
    void theSecretsAreThoseSetHereAndTheVaultsReplaceThem() {
        Settings s = settings();
        assertEquals(Map.of(), s.vaultSecrets());
        s.set("server.telegram.apiId", "12345");
        s.set("server.telegram.apiHash", "0123456789abcdef0123456789abcdef");
        s.set("gif.tenor.key", "t");
        s.set("cloud.dropboxAppKey", "d");
        s.set("cloud.secret", "never");
        s.set("server.port", "8008");
        s.set("ui.theme", "DARK");
        assertEquals(Map.of("server.telegram.apiId", "12345", "server.telegram.apiHash", "0123456789abcdef0123456789abcdef",
            "gif.tenor.key", "t", "cloud.dropboxAppKey", "d"), s.vaultSecrets());

        s.adoptVaultSecrets(Map.of("server.telegram.apiId", "999", "cloud.pcloudClientId", "p"));
        assertEquals(Map.of("server.telegram.apiId", "999", "cloud.pcloudClientId", "p"), s.vaultSecrets(),
            "what another computer cleared is cleared here too, as the settings are");
        assertEquals("never", s.getString("cloud.secret", null));
        assertEquals("8008", s.getString("server.port", null));
        assertEquals("DARK", s.getString("ui.theme", null));
        assertTrue(Settings.vaultSecret("gif.giphy.key"));
        assertFalse(Settings.vaultSecret("gif.giphy.enabled"));
    }
}

package app.unichat.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerSecretsTest {
    @TempDir
    Path dir;

    @Test
    void makesThemUpOnceAndKeepsThem() throws Exception {
        Properties first = new ServerSecrets(dir.resolve("new")).load();
        assertEquals(LocalServer.USER, first.getProperty("user"));
        assertEquals(24, first.getProperty("password").length());
        assertEquals(48, first.getProperty("registration_token").length());
        assertNotEquals(first.getProperty("registration_token"), first.getProperty("double_puppet_token"));
        assertEquals(first, new ServerSecrets(dir.resolve("new")).load());
    }

    @Test
    void whatIsSavedIsKeptAndOnlyWhatIsMissingIsAdded() throws Exception {
        Files.writeString(dir.resolve("secrets.properties"), "password=mine\naccount_created=yes\n");
        Properties p = new ServerSecrets(dir).load();
        assertEquals("mine", p.getProperty("password"));
        assertEquals("yes", p.getProperty("account_created"));
        assertTrue(p.containsKey("registration_token"));
        assertEquals(p, new ServerSecrets(dir).load());
    }

    @Test
    void saveKeepsWhatIsAddedLater() throws Exception {
        ServerSecrets secrets = new ServerSecrets(dir);
        Properties p = secrets.load();
        p.setProperty("account_created", "yes");
        secrets.save(p);
        assertEquals("yes", secrets.load().getProperty("account_created"));
    }
}

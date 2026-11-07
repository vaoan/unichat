package app.unichat.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bridge's setup, with a child JVM in place of the bridge: portable on macOS and Windows. */
class BridgeInstallerTest {
    private static final Manifest MANIFEST = Manifest.parse("""
            TUWUNEL_VERSION=1
            whatsapp=mautrix/whatsapp@v1:mautrix-whatsapp
            telegram=mautrix/telegram@v1:mautrix-telegram
            discord=mautrix/discord@v1:mautrix-discord
            """);
    private static final BridgeInstaller.TelegramApi KEYS = new BridgeInstaller.TelegramApi("12345", "abc");

    @TempDir
    Path dir;
    private BinaryStore store;

    @BeforeEach
    void marksTheBridgesInstalled() throws Exception {
        store = new BinaryStore(dir.resolve("bin"), Platform.MAC_ARM64, c -> "http://127.0.0.1:1/nothing-here");
        Files.createDirectories(dir.resolve("appservices"));
        Path anything = Files.writeString(dir.resolve("anything"), "x");
        for (String b : List.of("whatsapp", "telegram", "discord")) store.installBundled(b, anything, "v1");
    }

    private BridgeInstaller installer(String mode) {
        String java = ProcessHandle.current().info().command().orElse("java");
        return new BridgeInstaller(dir, store, MANIFEST,
                b -> List.of(java, "-cp", System.getProperty("java.class.path"), FakeService.class.getName(), mode));
    }

    private List<String> calls(String bridge) throws IOException {
        return Files.readAllLines(dir.resolve(bridge).resolve("calls.txt"));
    }

    @Test
    void generatesConfigAndRegistrationAndHandsThemToTheServer() throws Exception {
        installer("bridge").prepare("whatsapp", "dp-token", "http://127.0.0.1:9/status/whatsapp", null, m -> { });

        assertEquals(List.of("-e -c config.yaml", "-g -c config.yaml -r registration.yaml"), calls("whatsapp"));
        var config = BridgeConfig.read(Files.readString(dir.resolve("whatsapp/config.yaml")));
        assertEquals(LocalServer.URL, config.at("/homeserver/address").asText());
        assertEquals("localhost", config.at("/homeserver/domain").asText());
        assertEquals("as_token: fake\n", Files.readString(dir.resolve("appservices/whatsapp.yaml")));
    }

    @Test
    void anExistingConfigKeepsWhatTheBridgeWroteAndIsRewritten() throws Exception {
        BridgeInstaller installer = installer("bridge");
        installer.prepare("whatsapp", "dp-token", "http://127.0.0.1:9/one", null, m -> { });
        Files.delete(dir.resolve("appservices/whatsapp.yaml"));

        installer.prepare("whatsapp", "dp-token", "http://127.0.0.1:9/two", null, m -> { });

        assertEquals(2, calls("whatsapp").size(), "no setup process the second time");
        assertTrue(Files.readString(dir.resolve("whatsapp/config.yaml")).contains("http://127.0.0.1:9/two"));
        assertTrue(Files.exists(dir.resolve("appservices/whatsapp.yaml")));
    }

    @Test
    void aLegacyBridgeFillsAnEmptyConfigAndItsLogFileGoes() throws Exception {
        installer("bridge").prepare("discord", "dp-token", "http://127.0.0.1:9/status/discord", null, m -> { });

        assertEquals(List.of("-c config.yaml", "-g -c config.yaml -r registration.yaml"), calls("discord"));
        assertEquals(LocalServer.URL, BridgeConfig.read(Files.readString(dir.resolve("discord/config.yaml"))).at("/homeserver/address").asText());
        assertFalse(Files.exists(dir.resolve("discord/logs")), "it was written unfiltered");
    }

    @Test
    void aSetupFailureSaysItsLastLine() {
        IOException e = assertThrows(IOException.class, () -> installer("broken-bridge").prepare("whatsapp", "t", "u", null, m -> { }));
        assertTrue(e.getMessage().endsWith("no pudo prepararse: no database here"), e.getMessage());
    }

    @Test
    void aLegacyBridgeThatWritesNoConfigIsAnError() {
        IOException e = assertThrows(IOException.class, () -> installer("broken-bridge").prepare("discord", "t", "u", null, m -> { }));
        assertEquals("discord no generó su configuración", e.getMessage());
    }

    @Test
    void telegramTakesTheKeysItIsGiven() throws Exception {
        installer("bridge").prepare("telegram", "dp-token", "http://127.0.0.1:9/status/telegram", KEYS, m -> { });
        String config = Files.readString(dir.resolve("telegram/config.yaml"));
        assertTrue(config.contains("12345") && config.contains("abc"), "the keys reach its config");
    }

    @Test
    void telegramWithoutKeysIsAnError() {
        IOException e = assertThrows(IOException.class, () -> installer("bridge").prepare("telegram", "t", "u", null, m -> { }));
        assertTrue(e.getMessage().contains("api_id"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("appservices/telegram.yaml")));
    }

    @Test
    void aBridgeWithNoPinnedVersionIsAnError() {
        assertThrows(IOException.class, () -> installer("bridge").prepare("signal", "t", "u", null, m -> { }));
    }

    @Test
    void whatIsMissingIsDownloadedFirst() throws Exception {
        Files.delete(store.path("whatsapp"));
        List<String> progress = new ArrayList<>();
        assertThrows(IOException.class, () -> installer("bridge").prepare("whatsapp", "t", "u", null, progress::add), "nothing to download from here");
        assertEquals(List.of("Descargando el puente de whatsapp…"), progress);
        assertFalse(Files.exists(dir.resolve("whatsapp")), "nothing else happens until it's there");
    }
}

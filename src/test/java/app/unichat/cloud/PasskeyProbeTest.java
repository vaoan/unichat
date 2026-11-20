package app.unichat.cloud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Running the passkey probe: asked for by a flag, what it finds kept in a file the person can send. */
class PasskeyProbeTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    @AfterEach
    void flagOff() {
        System.clearProperty(PasskeyProbe.FLAG);
        PasskeyProbe.stop();
    }

    @Test
    void theArgumentAsksForItAsTheFlagDoes() {
        assertFalse(PasskeyProbe.asked(), "off unless asked");
        PasskeyProbe.fromArguments(new String[] {"--otro"});
        assertFalse(PasskeyProbe.asked());
        PasskeyProbe.fromArguments(new String[] {"--otro", PasskeyProbe.ARGUMENT});
        assertTrue(PasskeyProbe.asked(), "the installed app takes an argument, not a -D");
    }

    @Test
    void withoutTheFlagNothingOpens() throws Exception {
        URI[] opened = {null};
        assertNull(PasskeyProbe.onStart(u -> opened[0] = u, 0, dir.resolve("probe.txt")));
        assertNull(opened[0], "no browser, no port");
    }

    @Test
    void withTheFlagItOpensThePage() throws Exception {
        System.setProperty(PasskeyProbe.FLAG, "true");
        URI[] opened = {null};
        CompletableFuture<Void> probe = PasskeyProbe.onStart(u -> opened[0] = u, 0, dir.resolve("probe.txt"));
        assertEquals("/passkey", opened[0].getPath());
        assertFalse(probe.isDone());
    }

    @Test
    void eachFindingIsWrittenToTheFileWithoutThePrfBytes() throws Exception {
        Path file = dir.resolve("logs").resolve("passkey-probe.txt");
        URI[] opened = {null};
        List<PasskeyPage.Finding> told = new CopyOnWriteArrayList<>();
        PasskeyProbe.run(u -> opened[0] = u, told::add, 0, file);
        URI base = URI.create("http://127.0.0.1:" + opened[0].getPort());
        String page = HTTP.send(HttpRequest.newBuilder(base.resolve("/passkey")).build(), HttpResponse.BodyHandlers.ofString()).body();
        var m = Pattern.compile("const STATE = \"([0-9a-f]+)\"").matcher(page);
        assertTrue(m.find());
        for (String body : new String[] {"step=create&prfEnabled=true&credentialId=AAA", "step=get&prfLength=32&prf=U0VDUkVU"}) {
            HTTP.send(HttpRequest.newBuilder(base.resolve("/passkey/result"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("state=" + m.group(1) + "&" + body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(0).contains("step=create") && lines.get(0).contains("prfEnabled=true"), lines.get(0));
        assertTrue(lines.get(1).contains("step=get") && lines.get(1).contains("prfLength=32"), lines.get(1));
        assertTrue(lines.get(1).contains("unichatOs="), "which computer it ran on");
        assertFalse(Files.readString(file).contains("U0VDUkVU"));
        assertEquals(2, told.size(), "the screen hears each finding too");
    }

    @Test
    void runningItAgainEndsTheOneBefore() throws Exception {
        URI[] opened = {null};
        CompletableFuture<Void> first = PasskeyProbe.run(u -> opened[0] = u, f -> { }, 0, dir.resolve("probe.txt"));
        URI firstAddress = opened[0];
        PasskeyProbe.run(u -> opened[0] = u, f -> { }, 0, dir.resolve("probe.txt"));
        assertTrue(first.isCancelled(), "the port 43117 is one: the one before gives it back");
        PCloudTest.portIsFree(firstAddress.toString());
    }

    @Test
    void theFileIsInTheLogsFolder() {
        assertEquals(app.unichat.settings.Settings.dataDir().resolve("logs").resolve("passkey-probe.txt"), PasskeyProbe.file());
    }
}

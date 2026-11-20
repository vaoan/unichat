package app.unichat.cloud;

import app.unichat.settings.Settings;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Every cloud test starts with no vault held here ({@link NoVaultHeld}): a run cut short leaves its keys behind. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NoVaultHeldTest {
    private static final Pattern KEY = Pattern.compile("\"(cloud\\.vault\\.[A-Za-z]+)\"");

    @Test
    @Order(1)
    void aTestLeavesTheVaultsKeysBehindAsAKilledRunWould() {
        for (String k : NoVaultHeld.KEYS) Settings.get().set(k, "left-by-a-run-cut-short");
    }

    @Test
    @Order(2)
    void theNextStartsWithNoneOfThem() {
        for (String k : NoVaultHeld.KEYS) assertNull(Settings.get().getString(k, null), k);
    }

    @Test
    void everyVaultKeyTheAppKeepsIsReset() throws IOException {
        Set<String> inSource = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(p));
                while (m.find()) inSource.add(m.group(1));
            }
        }
        assertEquals(inSource, new TreeSet<>(NoVaultHeld.KEYS));
    }
}

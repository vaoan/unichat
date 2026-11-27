package app.unichat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JVM the installed app runs on: with Java's defaults G1 kept the whole 1 GB heap committed while ~265 MB were live
 * (measured with {@code jcmd}: 794 MB resident, 546 MB once it gave back what it no longer used).
 */
class PackagingJvmTest {
    /** A 1 GB ceiling (the animations' budgets assume it), and G1 giving memory back: idle collections, little kept free; and JavaFX's texture pool at 256 MB (512 by default). */
    private static final List<String> OPTIONS = List.of("-Xmx1g", "-XX:G1PeriodicGCInterval=60000", "-XX:MinHeapFreeRatio=15",
            "-XX:MaxHeapFreeRatio=35", "-Dprism.maxvram=256m");

    @Test
    void theMacAndLinuxPackageGivesMemoryBack() throws IOException {
        assertEquals(OPTIONS, options("package.sh", "--java-options\\s+(\\S+?)\"?(?=\\s|$)"));
    }

    @Test
    void theWindowsPackageRunsTheSameJvm() throws IOException {
        assertEquals(OPTIONS, options("package.ps1", "\"--java-options\",\\s*\"([^\"]+)\""));
    }

    private static List<String> options(String script, String pattern) throws IOException {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile(pattern).matcher(Files.readString(Path.of(script)));
        while (m.find()) found.add(m.group(1));
        return found;
    }
}

package app.unichat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The patched macOS {@code libglass.dylib} (native/build-glass.sh): it is built from the OpenJFX tag the app runs on, so
 * a JavaFX bump must come with a re-checked patch, or the old patch would be applied to other sources.
 */
class GlassPatchTest {
    private static final Path SCRIPT = Path.of("native/build-glass.sh");
    private static final Path PATCHES = Path.of("native/patches/javafx");
    private static final String GLASS = "modules/javafx.graphics/src/main/native-glass/mac/";

    @Test
    void theScriptBuildsTheJavaFxVersionTheAppRunsOn() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertEquals(find(pom, "<javafx.version>([^<]+)</javafx.version>"), find(Files.readString(SCRIPT), "(?m)^JFX_VERSION=(\\S+)"),
                "pom.xml's javafx.version changed: re-check native/patches/javafx against the new tag, then bump JFX_VERSION, JFX_TAG, JFX_COMMIT and the jar hashes in native/build-glass.sh");
        assertEquals(find(Files.readString(SCRIPT), "(?m)^JFX_VERSION=(\\S+)") + "-ga", find(Files.readString(SCRIPT), "(?m)^JFX_TAG=(\\S+)"));
    }

    @Test
    void thePatchTouchesTheThreeGlassFilesAndNothingElse() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(PATCHES)) {
            files = s.filter(p -> p.toString().endsWith(".patch")).toList();
        }
        assertEquals(List.of("glass-layer-leak.patch"), files.stream().map(p -> PATCHES.relativize(p).toString()).toList());
        String patch = Files.readString(files.get(0));
        Matcher plus = Pattern.compile("(?m)^\\+\\+\\+ b/(\\S+)$").matcher(patch);
        List<String> added = new java.util.ArrayList<>();
        while (plus.find()) added.add(plus.group(1));
        assertEquals(List.of(GLASS + "GlassLayer.m", GLASS + "GlassMTLOffscreen.m", GLASS + "GlassView3D.m"), added);
        Matcher m = Pattern.compile("(?m)^diff --git a/(\\S+) b/(\\S+)$").matcher(patch);
        List<String> touched = new java.util.ArrayList<>();
        while (m.find()) {
            assertEquals(m.group(1), m.group(2));
            touched.add(m.group(1));
        }
        assertEquals(List.of(GLASS + "GlassLayer.m", GLASS + "GlassMTLOffscreen.m", GLASS + "GlassView3D.m"), touched);
    }

    @Test
    void theMacPackageShipsItNextToTheJar() throws IOException {
        String pkg = Files.readString(Path.of("package.sh"));
        assertTrue(pkg.contains("native/build-glass.sh \"$PWD/target/pkg/libglass.dylib\""), "package.sh must put libglass.dylib beside unichat.jar");
        assertTrue(pkg.contains("REQUIRE_GLASS"), "package.sh must be able to refuse a package without the patch");
        assertTrue(Files.readString(Path.of(".github/workflows/build.yml")).contains("REQUIRE_GLASS"), "CI's macOS package must require it");
        assertTrue(Files.readString(Path.of("install-mac.sh")).contains("libglass.dylib"), "install-mac.sh must refuse a package without it");
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), regex);
        return m.group(1);
    }
}

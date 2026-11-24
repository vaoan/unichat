package app.unichat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** No file under {@code .mvn} turns off a check that {@link BuildRulesTest} keeps on in pom.xml. */
class MavenConfigRulesTest {

    @Test
    void noMavenConfigFileSkipsACheckEither() throws IOException {
        // .mvn/maven.config adds command-line options to every build (-Dpmd.skip, -Dcheckstyle.skip, -Dmaven.test.skip ...),
        // and a -D in .mvn/jvm.config sets a property the same way: neither is read in pom.xml.
        assertFalse(Files.exists(Path.of(".mvn/maven.config")), ".mvn/maven.config adds options to every build");
        for (String line : Files.readAllLines(Path.of(".mvn/jvm.config"))) {
            assertTrue(opensACompilerPackage(line),
                    ".mvn/jvm.config only opens the compiler's packages to Error Prone (--add-exports, --add-opens): " + line);
        }
    }

    @Test
    void aJvmConfigLineCarriesNothingButTheOpening() {
        assertTrue(opensACompilerPackage("--add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED"));
        assertTrue(opensACompilerPackage("--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"));
        assertTrue(opensACompilerPackage(""));
        assertFalse(opensACompilerPackage("--add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED -Dpmd.skip=true"),
                "a -D after the opening on the same line");
        assertFalse(opensACompilerPackage("-Dcheckstyle.skip=true"));
        assertFalse(opensACompilerPackage("--add-opens=jdk.compiler/x=ALL-UNNAMED"));
    }

    /** A blank line, or one {@code --add-exports}/{@code --add-opens} of one package and nothing else on the line. */
    private static boolean opensACompilerPackage(String line) {
        return line.isBlank() || line.strip().matches("--add-(exports|opens) [\\w.]+/[\\w.]+=ALL-UNNAMED");
    }
}

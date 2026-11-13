package app.unichat;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The run's temp folder (registered for all, `META-INF/services`): surefire points {@code java.io.tmpdir} at
 * target/test-tmp (pom.xml); it's emptied when the run starts (what a run cut short left) and when it ends. Run
 * outside Maven (an IDE), the temp folder is the system's and is left alone.
 */
public final class TestTemp implements BeforeAllCallback {
    private static final Path FOLDER = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
    private static final boolean OURS = FOLDER.endsWith(Path.of("target", "test-tmp"));

    static {
        // Before any test's class makes a temp file: the folder must be there.
        if (OURS) {
            try {
                Files.createDirectories(FOLDER);
                empty(FOLDER);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Where this run's temp files go. */
    public static Path folder() {
        return FOLDER;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!OURS) return;
        // Once for the whole run: the root's store closes its resources when every test is done.
        context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).getOrComputeIfAbsent(TestTemp.class,
                k -> (ExtensionContext.Store.CloseableResource) () -> empty(FOLDER), Object.class);
    }

    /** Deletes everything inside {@code dir}, keeping it; what can't be deleted (still open) is left. */
    static void empty(Path dir) throws IOException {
        try (Stream<Path> all = Files.walk(dir)) {
            all.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(dir)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // in use or not empty yet: the next run's start takes it
                }
            });
        }
    }
}

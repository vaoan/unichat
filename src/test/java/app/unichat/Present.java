package app.unichat;

import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A value a test goes on with: the test fails when it's null, else it's given back as not null. For a call that may
 * answer null ({@code chat(id)}, a file's bytes, a slot) where the test knows it doesn't.
 */
public final class Present {
    private Present() {}

    public static <T> T of(@Nullable T value) {
        assertNotNull(value, "expected a value, got null");
        return value;
    }

    /** A tool this computer may lack ({@code Ffmpeg.find()}): the test is skipped, saying {@code why}, when it's null. */
    public static <T> T orSkip(@Nullable T value, String why) {
        assumeTrue(value != null, why);
        return value;
    }

    /** What the failure {@code failed} wrapped says (a future's {@code ExecutionException}, a {@code CompletionException}). */
    public static String message(Throwable failed) {
        return of(of(failed.getCause()).getMessage());
    }
}

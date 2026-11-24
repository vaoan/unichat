package app.unichat;

import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    /** What the failure {@code failed} wrapped says (a future's {@code ExecutionException}, a {@code CompletionException}). */
    public static String message(Throwable failed) {
        return of(of(failed.getCause()).getMessage());
    }
}

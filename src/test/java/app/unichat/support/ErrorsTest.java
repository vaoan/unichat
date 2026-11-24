package app.unichat.support;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ErrorsTest {
    @Test
    void unwrapsOnlyTheWrappers() {
        IOException root = new IOException("raíz");
        RuntimeException real = new RuntimeException("real", root);
        assertSame(real, Errors.unwrap(new CompletionException(new ExecutionException(real))));
        assertSame(real, Errors.unwrap(real), "a cause of a real exception stays behind it");
    }

    @Test
    @SuppressFBWarnings(value = "NP_NONNULL_PARAM_VIOLATION", justification = "CompletionException takes a null cause (the JDK allows it); the test checks that case")
    void aWrapperWithoutCauseStays() {
        CompletionException lonely = new CompletionException("sola", null);
        assertSame(lonely, Errors.unwrap(lonely));
    }

    @Test
    void neverSaysNull() {
        assertEquals("NullPointerException", Errors.message(new NullPointerException()));
        assertEquals("NullPointerException", Errors.message(new CompletionException(new NullPointerException())));
        assertEquals("IOException", Errors.message(new IOException(" ")));
    }

    @Test
    void theMessageOfWhatHappenedNotOfTheWrapper() {
        assertEquals("sin red", Errors.message(new CompletionException(new IOException("sin red"))));
    }
}

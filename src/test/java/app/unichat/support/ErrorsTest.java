package app.unichat.support;

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

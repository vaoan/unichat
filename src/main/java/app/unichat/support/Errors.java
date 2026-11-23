package app.unichat.support;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** What to say about an exception. */
public final class Errors {
    private Errors() {}

    /** Without the wrappers futures add; any other exception stays, even if it has a cause. */
    public static Throwable unwrap(Throwable t) {
        Throwable out = t;
        while ((out instanceof CompletionException || out instanceof ExecutionException) && out.getCause() != null) out = out.getCause();
        return out;
    }

    /** The message of {@code t} or, for exceptions that have none, its class name: never "null". */
    public static String message(Throwable t) {
        Throwable real = unwrap(t);
        String m = real.getMessage();
        return m == null || m.isBlank() ? real.getClass().getSimpleName() : m;
    }
}

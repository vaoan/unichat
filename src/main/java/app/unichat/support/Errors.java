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

    /**
     * An exception whose message is already what the person reads (a translated text): {@link #shown} stops there,
     * and its cause is only for the log.
     */
    public interface Told {}

    /**
     * What a screen shows of {@code t}: its deepest cause's message, as a bridge's login step says what went wrong and
     * comes wrapped in whatever carried it here; but never past a {@link Told}, whose cause is the raw one.
     */
    public static String shown(Throwable t) {
        Throwable root = t;
        while (!(root instanceof Told) && root.getCause() != null) root = root.getCause();
        return message(root);
    }

    /** The message of {@code t} or, for exceptions that have none, its class name: never "null". */
    public static String message(Throwable t) {
        Throwable real = unwrap(t);
        String m = real.getMessage();
        return m == null || m.isBlank() ? real.getClass().getSimpleName() : m;
    }
}

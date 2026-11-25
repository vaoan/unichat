package app.unichat.service;

import org.jspecify.annotations.Nullable;

import java.io.IOException;

/**
 * A file that can't go as it was asked (a video too long to be a moving picture, a text file as one): trying again won't
 * change it. The message says why, in the person's words; {@link UploadQueue} gives it up at once and shows it.
 */
public final class CantSend extends IOException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public CantSend(String why) {
        super(why);
    }

    /** {@code why} is the cause's message, which a tool's failure may lack. */
    public CantSend(@Nullable String why, Throwable cause) {
        super(why, cause);
    }

    /** The {@link CantSend} behind a failure (it comes wrapped by the futures it went through), or null. */
    public static @Nullable CantSend behind(@Nullable Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof CantSend c) return c;
        }
        return null;
    }
}

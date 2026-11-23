package app.unichat.service;

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

    public CantSend(String why, Throwable cause) {
        super(why, cause);
    }
}

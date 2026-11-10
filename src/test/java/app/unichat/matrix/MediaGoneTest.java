package app.unichat.matrix;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Autosave tries a failed file again at the chat's next change, unless it's gone for good: which errors say that. */
class MediaGoneTest {
    @Test
    void notThereOrRefusedForGood() {
        assertTrue(MediaDownloader.gone(new MatrixException(404, "M_NOT_FOUND", "Not found")), "deleted or expired");
        assertTrue(MediaDownloader.gone(new MatrixException(410, null, "HTTP 410")));
        assertTrue(MediaDownloader.gone(new CompletionException(new MatrixException(404, null, "HTTP 404"))), "as a future gives it");
        assertTrue(MediaDownloader.gone(new MatrixException(0, null, "not an address it opens")), "our own refusal");
    }

    @Test
    void whatMayWorkNextTime() {
        assertFalse(MediaDownloader.gone(new MatrixException(502, null, "HTTP 502")), "the bridge, for now");
        assertFalse(MediaDownloader.gone(new MatrixException(429, "M_LIMIT_EXCEEDED", "slow down")));
        assertFalse(MediaDownloader.gone(new MatrixException("connection reset", new IOException("reset"))), "the network");
        assertFalse(MediaDownloader.gone(new IOException("No space left on device")));
    }
}

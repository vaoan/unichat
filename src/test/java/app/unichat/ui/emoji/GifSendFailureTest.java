package app.unichat.ui.emoji;

import app.unichat.service.CantSend;
import app.unichat.support.I18n;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The notice when a GIF from the GIF tab didn't go: why, when it can't go as it is, else that it failed. */
class GifSendFailureTest {
    @Test
    void aGifThatCantGoSaysWhy() {
        String why = I18n.t("gif.cantMakeMp4", "Telegram");
        assertEquals(why, GifGrid.failure(new CompletionException(new CantSend(why, new IOException("ffmpeg failed")))),
                "a WebM that couldn't be made the MP4 Telegram takes");
    }

    @Test
    void anyOtherFailureSaysItFailed() {
        assertEquals(I18n.t("gif.sendFailed"), GifGrid.failure(new CompletionException(new IOException("connection reset"))));
    }
}

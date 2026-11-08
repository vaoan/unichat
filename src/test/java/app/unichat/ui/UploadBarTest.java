package app.unichat.ui;

import app.unichat.service.UploadQueue;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the upload bar says about the file going up and the batch (the tests run in Spanish). */
class UploadBarTest {
    private static final UploadQueue.Upload FILE = new UploadQueue.Upload("!c", Path.of("IMG_0019.webp"), null, app.unichat.service.SendAs.DOCUMENT);

    private static UploadQueue.Status status(double progress, int retries, UploadQueue.Phase phase, long retryAt) {
        return new UploadQueue.Status(FILE, 32, 0, 1288, progress, retries, phase, retryAt, -1);
    }

    @Test
    void saysWhereTheCurrentFileIs() {
        assertEquals("subiendo 45 %", UploadBar.stage(status(0.459, 0, UploadQueue.Phase.UPLOADING, 0), "Telegram", 0));
        assertEquals("esperando a que Telegram la reciba…", UploadBar.stage(status(1, 0, UploadQueue.Phase.DELIVERING, 0), "Telegram", 0));
        assertEquals("falló 2 veces · reintento en 25 s",
                UploadBar.stage(status(0, 2, UploadQueue.Phase.RETRYING, 25_000), "Telegram", 0));
        assertEquals("subiendo 10 % · intento 3", UploadBar.stage(status(0.1, 2, UploadQueue.Phase.UPLOADING, 0), "Telegram", 0));
    }

    @Test
    void numbersForTheWholeBatch() {
        assertEquals("2 %", UploadBar.percent(0.025));
        assertEquals("99 %", UploadBar.percent(0.996), "100 only when all is done");
        assertEquals("100 %", UploadBar.percent(1));
        assertEquals("falta menos de un minuto", UploadBar.eta(20_000));
        assertEquals("faltan ~12 min", UploadBar.eta(12 * 60_000));
        assertEquals("faltan ~1 h 20 min", UploadBar.eta(80 * 60_000));
        assertEquals("", UploadBar.eta(-1));
    }
}
